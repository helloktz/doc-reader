use std::{io::{Read, Write}, sync::Mutex};
use jni::{JNIEnv, objects::{JObject, JString}, sys::jstring};

static CONVERSION: Mutex<()> = Mutex::new(());
const MAX_INPUT: u64 = 64 * 1024 * 1024;

pub fn convert(input: &str, output: &str) -> Result<(), String> {
    let _guard = CONVERSION.lock().map_err(|_| "转换器需要重新启动，请重新打开应用".to_string())?;
    let source = std::fs::File::open(input).map_err(|e| e.to_string())?;
    let mut bytes = Vec::new();
    source.take(MAX_INPUT + 1).read_to_end(&mut bytes).map_err(|e| e.to_string())?;
    if bytes.len() as u64 > MAX_INPUT { return Err("DOCX 超过 64 MB，暂时无法打开".into()); }
    let doc = wordcraft_docx::read(&bytes).map_err(|e| format!("无法读取 DOCX：{e}"))?;
    let fonts = wordcraft_fonts::FontDb::global();
    let embedded = wordcraft_docx::embedded_fonts(&doc).into_iter()
        .filter_map(|f| wordcraft_fonts::decode_font_file(&f.data).ok().map(|data| (data, f.name)))
        .collect();
    fonts.add_document_fonts(embedded);
    let pdf = wordcraft_pdf::export(&doc, &Default::default()).map_err(|e| format!("无法生成 PDF：{e}"))?;
    let mut file = std::fs::File::create(output).map_err(|e| e.to_string())?;
    file.write_all(&pdf).map_err(|e| e.to_string())?;
    file.sync_all().map_err(|e| e.to_string())
}

// The only JNI boundary. Panics cannot unwind across the VM's C ABI.
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_helloktz_docreader_NativeBridge_convert(
    mut env: JNIEnv, _this: JObject, input: JString, output: JString,
) -> jstring {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| -> Result<(), String> {
        let input: String = env.get_string(&input).map_err(|e| e.to_string())?.into();
        let output: String = env.get_string(&output).map_err(|e| e.to_string())?.into();
        convert(&input, &output)
    }));
    let error = match result { Ok(Ok(())) => return std::ptr::null_mut(), Ok(Err(e)) => e,
        Err(_) => "文档转换失败，请重新打开应用后尝试其他文件".into() };
    match env.new_string(error) { Ok(s) => s.into_raw(), Err(_) => {
        let _ = env.throw_new("java/lang/RuntimeException", "无法转换文档");
        std::ptr::null_mut()
    }}
}
