package io.github.helloktz.docreader;

import android.app.Instrumentation;
import android.app.Activity;
import android.os.Bundle;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.pdf.PdfRenderer;
import android.os.ParcelFileDescriptor;
import android.view.accessibility.AccessibilityNodeInfo;
import java.io.*;

/** Exercises the actual JNI library and platform renderer, without network access. */
public class DocumentIntegrationTest extends Instrumentation {
    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            testDocxToReadablePdfAndMalformedInput();
            testSiblingExport();
            testMinimalHomeAndReading();
            result.putString("stream", "PASS: conversion, PDF outline, sibling export/overwrite/suffix, minimal home, pinch fallback, fit width, size lock, vertical scroll, fling, activity recreation\n");
            finish(Activity.RESULT_OK, result);
        } catch (Throwable error) {
            result.putString("stream", android.util.Log.getStackTraceString(error));
            finish(Activity.RESULT_CANCELED, result);
        }
    }
    private Instrumentation getInstrumentation() { return this; }
    private static void assertTrue(boolean value) { assertTrue("Expected true", value); }
    private static void assertTrue(String message, boolean value) { if (!value) throw new AssertionError(message); }
    private static void assertNull(Object value) { if (value != null) throw new AssertionError("Expected null: " + value); }
    private static void assertNotNull(Object value) { assertNotNull("Expected a value", value); }
    private static void assertNotNull(String message, Object value) { if (value == null) throw new AssertionError(message); }
    private static void assertEquals(int expected, int actual) { if (expected != actual) throw new AssertionError(expected + " != " + actual); }
    public void testDocxToReadablePdfAndMalformedInput() throws Exception {
        android.content.Context context = getInstrumentation().getTargetContext();
        File input = new File(context.getCacheDir(), "integration.docx");
        File output = new File(context.getCacheDir(), "integration.pdf");
        try (InputStream source = getContext().getAssets().open("sample.docx"); OutputStream target = new FileOutputStream(input)) {
            source.transferTo(target);
        }
        assertNull(NativeBridge.INSTANCE.convert(input.getAbsolutePath(), output.getAbsolutePath()));
        assertTrue(output.length() > 1000);
        org.json.JSONArray outline = new org.json.JSONArray(NativeBridge.INSTANCE.outline(output.getAbsolutePath()));
        assertEquals(2, outline.length());
        assertEquals(0, outline.getJSONObject(0).getInt("page"));
        assertEquals(1, outline.getJSONObject(1).getInt("page"));
        assertTrue(outline.getJSONObject(1).getString("title").contains("第二页"));
        try (PdfRenderer renderer = new PdfRenderer(ParcelFileDescriptor.open(output, ParcelFileDescriptor.MODE_READ_ONLY))) {
            assertEquals(2, renderer.getPageCount());
            for (int i = 0; i < renderer.getPageCount(); i++) {
                try (PdfRenderer.Page page = renderer.openPage(i)) {
                    Bitmap bitmap = Bitmap.createBitmap(600, 850, Bitmap.Config.ARGB_8888);
                    bitmap.eraseColor(Color.WHITE);
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                    int dark = 0;
                    for (int y = 0; y < bitmap.getHeight(); y += 3)
                        for (int x = 0; x < bitmap.getWidth(); x += 3)
                            if ((bitmap.getPixel(x, y) & 0xffffff) < 0x999999) dark++;
                    assertTrue("Page must contain rendered ink", dark > 30);
                    bitmap.recycle();
                }
            }
        }
        try (FileOutputStream target = new FileOutputStream(input)) { target.write("not a docx".getBytes()); }
        assertNotNull(NativeBridge.INSTANCE.convert(input.getAbsolutePath(), new File(context.getCacheDir(), "invalid.pdf").getAbsolutePath()));
        input.delete();
    }

    public void testSiblingExport() throws Exception {
        File directory = new File(getTargetContext().getCacheDir(), "export-test");
        directory.mkdirs();
        File source = new File(directory, "book.docx");
        try (InputStream input = getContext().getAssets().open("sample.docx"); OutputStream output = new FileOutputStream(source)) { input.transferTo(output); }
        byte[] original = java.nio.file.Files.readAllBytes(source.toPath());
        File pdf = new File(getTargetContext().getCacheDir(), "integration.pdf");
        for (String name : new String[]{"book.pdf", "book (1).pdf", "book (2).pdf"}) new File(directory, name).delete();
        File first = SameFolderExport.INSTANCE.save(pdf, source, false);
        assertTrue(first.getName().equals("book.pdf"));
        File both = SameFolderExport.INSTANCE.save(pdf, source, false);
        assertTrue(both.getName().equals("book (1).pdf"));
        assertTrue(SameFolderExport.INSTANCE.save(pdf, source, false).getName().equals("book (2).pdf"));
        java.nio.file.Files.write(first.toPath(), "old pdf".getBytes());
        assertTrue(SameFolderExport.INSTANCE.save(pdf, source, true).equals(first));
        assertTrue(java.util.Arrays.equals(java.nio.file.Files.readAllBytes(pdf.toPath()), java.nio.file.Files.readAllBytes(first.toPath())));
        assertTrue(java.util.Arrays.equals(original, java.nio.file.Files.readAllBytes(source.toPath())));
    }

    public void testMinimalHomeAndReading() throws Exception {
        MainActivity activity = (MainActivity) getInstrumentation().startActivitySync(
            new Intent(getInstrumentation().getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        try {
            assertNotNull("Home must offer file opening", waitForText("打开文件", 10000));
            getInstrumentation().runOnMainSync(() -> assertEquals(1, visibleLabelCount(activity.getWindow().getDecorView())));
            File input = new File(getTargetContext().getCacheDir(), "ui.docx");
            try (InputStream source = getContext().getAssets().open("sample.docx"); OutputStream target = new FileOutputStream(input)) {
                source.transferTo(target);
            }
            getInstrumentation().runOnMainSync(() -> activity.onActivityResult(10, Activity.RESULT_OK,
                new Intent().setData(android.net.Uri.fromFile(input))));
            assertNotNull("DOCX viewer must offer PDF export", waitForText("导出 PDF", 30000));
            assertNotNull("Page counter must be visible", waitForText("1 / 2", 10000));
            Thread.sleep(800);
            testPinchAndDoubleTap(activity);
            testFling(activity);
            runOnMainSync(() -> {
                PdfCanvas view = findCanvas(activity.getWindow().getDecorView());
                view.jumpToPage(0); view.setSizeLocked(true);
            });
            getInstrumentation().runOnMainSync(activity::recreate);
            getInstrumentation().waitForIdleSync();
            assertNotNull("Document must survive activity recreation", waitForText("1 / 2", 10000));
            assertNotNull("Lock state must survive activity recreation", waitForText("解锁尺寸", 10000));
        } finally { getInstrumentation().runOnMainSync(activity::finish); }
    }

    private int visibleLabelCount(android.view.View view) {
        if (!view.isShown()) return 0;
        if (view instanceof android.widget.TextView)
            return ((android.widget.TextView) view).getText().length() > 0 ? 1 : 0;
        int count = 0;
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) count += visibleLabelCount(group.getChildAt(i));
        }
        return count;
    }

    private PdfCanvas findCanvas(android.view.View view) {
        if (view instanceof PdfCanvas) return (PdfCanvas) view;
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                PdfCanvas child = findCanvas(group.getChildAt(i));
                if (child != null) return child;
            }
        }
        return null;
    }

    private void testPinchAndDoubleTap(MainActivity activity) throws Exception {
        runOnMainSync(() -> {
            PdfCanvas view = findCanvas(activity.getWindow().getDecorView());
            assertNotNull(view);
            assertTrue("Fit width starts at 1x", view.getZoomLevel() == 1f);
            long now = android.os.SystemClock.uptimeMillis();
            float cx = view.getWidth() / 2f, cy = Math.min(300f, view.getHeight() / 3f);
            android.view.MotionEvent down = android.view.MotionEvent.obtain(now, now, 0, cx - 150, cy, 0);
            view.dispatchTouchEvent(down); down.recycle();
            sendTwoPointers(view, now, now + 20, 5 | (1 << 8), cx, cy, 150);
            for (int i = 1; i <= 6; i++) sendTwoPointers(view, now, now + 20 + i * 20, 2, cx, cy, 150 + i * 40);
            sendTwoPointers(view, now, now + 160, 6 | (1 << 8), cx, cy, 390);
            android.view.MotionEvent up = android.view.MotionEvent.obtain(now, now + 180, 1, cx - 390, cy, 0);
            view.dispatchTouchEvent(up); up.recycle();
            assertTrue("Pinch must change scale", view.getZoomLevel() > 1.2f);
            assertTrue("Old page image must remain visible before sharper rendering", inkCount(view) > 30);
            float zoom = view.getZoomLevel(), x = view.getHorizontalOffset(), y = view.getVerticalOffset();
            view.setSizeLocked(true);
            singleDrag(view, cx, 900, cx + 180, 500);
            sendTwoPointers(view, now + 500, now + 520, 5 | (1 << 8), cx, cy, 150);
            sendTwoPointers(view, now + 500, now + 550, 2, cx, cy, 350);
            sendTwoPointers(view, now + 500, now + 570, 6 | (1 << 8), cx, cy, 350);
            view.fitWidth();
            assertTrue("Lock preserves scale", view.getZoomLevel() == zoom);
            assertTrue("Lock prevents sideways movement", view.getHorizontalOffset() == x);
            assertTrue("Lock allows vertical reading", view.getVerticalOffset() > y);
            view.setSizeLocked(false);
        });
        runOnMainSync(() -> {
            PdfCanvas view = findCanvas(activity.getWindow().getDecorView());
            long now = android.os.SystemClock.uptimeMillis() + 500;
            for (int i = 0; i < 2; i++) {
                android.view.MotionEvent down = android.view.MotionEvent.obtain(now + i * 100, now + i * 100, 0, 500, 600, 0);
                android.view.MotionEvent up = android.view.MotionEvent.obtain(now + i * 100, now + i * 100 + 30, 1, 500, 600, 0);
                view.dispatchTouchEvent(down); view.dispatchTouchEvent(up); down.recycle(); up.recycle();
            }
            assertTrue("Double tap resets zoom", view.getZoomLevel() == 1f);
            assertTrue("Fit has no horizontal offset", view.getHorizontalOffset() == 0f);
        });
    }

    private int inkCount(PdfCanvas view) {
        Bitmap bitmap = Bitmap.createBitmap(view.getWidth(), view.getHeight(), Bitmap.Config.ARGB_8888);
        view.draw(new android.graphics.Canvas(bitmap));
        int ink = 0;
        for (int y = 0; y < bitmap.getHeight(); y += 4) for (int x = 0; x < bitmap.getWidth(); x += 4)
            if ((bitmap.getPixel(x, y) & 0xffffff) < 0x999999) ink++;
        bitmap.recycle();
        return ink;
    }

    private void singleDrag(PdfCanvas view, float x1, float y1, float x2, float y2) {
        long now = android.os.SystemClock.uptimeMillis();
        for (int i = 0; i <= 5; i++) {
            int action = i == 0 ? 0 : i == 5 ? 1 : 2;
            android.view.MotionEvent event = android.view.MotionEvent.obtain(now, now + i * 20, action,
                x1 + (x2 - x1) * i / 5, y1 + (y2 - y1) * i / 5, 0);
            view.dispatchTouchEvent(event); event.recycle();
        }
    }

    private void testFling(MainActivity activity) throws Exception {
        float[] before = new float[1];
        runOnMainSync(() -> {
            PdfCanvas view = findCanvas(activity.getWindow().getDecorView());
            view.jumpToPage(0);
            singleDrag(view, 400, 900, 400, 600);
            before[0] = view.getVerticalOffset();
        });
        Thread.sleep(200);
        runOnMainSync(() -> assertTrue("Scroll must continue after the finger lifts",
            findCanvas(activity.getWindow().getDecorView()).getVerticalOffset() > before[0]));
    }

    private void sendTwoPointers(PdfCanvas view, long downTime, long time, int action, float cx, float cy, float radius) {
        android.view.MotionEvent.PointerProperties[] props = new android.view.MotionEvent.PointerProperties[2];
        android.view.MotionEvent.PointerCoords[] coords = new android.view.MotionEvent.PointerCoords[2];
        for (int i = 0; i < 2; i++) {
            props[i] = new android.view.MotionEvent.PointerProperties(); props[i].id = i; props[i].toolType = 1;
            coords[i] = new android.view.MotionEvent.PointerCoords(); coords[i].x = cx + (i == 0 ? -radius : radius); coords[i].y = cy;
            coords[i].pressure = 1; coords[i].size = 1;
        }
        android.view.MotionEvent event = android.view.MotionEvent.obtain(downTime, time, action, 2, props, coords, 0, 0, 1, 1, 0, 0, android.view.InputDevice.SOURCE_TOUCHSCREEN, 0);
        view.dispatchTouchEvent(event); event.recycle();
    }

    private AccessibilityNodeInfo waitForText(String text, long timeout) throws Exception {
        long deadline = android.os.SystemClock.uptimeMillis() + timeout;
        while (android.os.SystemClock.uptimeMillis() < deadline) {
            AccessibilityNodeInfo root = getInstrumentation().getUiAutomation().getRootInActiveWindow();
            if (root != null) {
                for (AccessibilityNodeInfo node : root.findAccessibilityNodeInfosByText(text))
                    if (node.isVisibleToUser()) return node;
            }
            Thread.sleep(200);
        }
        return null;
    }
}
