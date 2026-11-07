fn main() {
    let args: Vec<_> = std::env::args().collect();
    if args.len() != 3 { eprintln!("Usage: convert input.docx output.pdf"); std::process::exit(2); }
    if let Err(e) = doc_reader::convert(&args[1], &args[2]) { eprintln!("{e}"); std::process::exit(1); }
}
