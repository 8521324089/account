#!/usr/bin/env python3
import os
import sys
import time
import subprocess
import zlib
import struct
import re
import xml.etree.ElementTree as ET

ADB_BIN = "/Users/vivekkumar/Library/Android/sdk/platform-tools/adb"
DEFAULT_SERIAL = "emulator-5554"
SERIAL = os.environ.get("ADB_TARGET_SERIAL", DEFAULT_SERIAL)
PACKAGE = "com.kb.ledger"
APK_PATH = "app/build/outputs/apk/release/app-release.apk"
EVIDENCE_DIR = "release-evidence/gallery_flow"

os.makedirs(EVIDENCE_DIR, exist_ok=True)

def run_adb(args, timeout=30):
    cmd = [ADB_BIN, "-s", SERIAL] + args
    res = subprocess.run(cmd, capture_output=True, text=True, timeout=timeout)
    return res

def check_safety():
    print(f"Checking safety on target: {SERIAL}...")
    res = run_adb(["devices", "-l"])
    lines = res.stdout.strip().splitlines()
    target_line = [l for l in lines if SERIAL in l]
    if not target_line:
        print(f"FATAL: Target {SERIAL} not found in adb devices!")
        sys.exit(1)
    
    char_res = run_adb(["shell", "getprop", "ro.build.characteristics"])
    characteristics = char_res.stdout.strip()
    print(f"ro.build.characteristics: '{characteristics}'")
    if "emulator" not in characteristics:
        print(f"FATAL: Target {SERIAL} is NOT an emulator! characteristics={characteristics}")
        sys.exit(1)
    print("Safety check PASSED: Target is confirmed an isolated Android emulator.")

def take_screenshot(filename):
    filepath = os.path.join(EVIDENCE_DIR, filename)
    run_adb(["shell", "screencap", "-p", "/data/local/tmp/gallery_shot.png"])
    subprocess.run([ADB_BIN, "-s", SERIAL, "pull", "/data/local/tmp/gallery_shot.png", filepath], capture_output=True, check=True)
    print(f"[SCREENSHOT] Saved: {filepath}")
    return filepath

def dump_ui():
    run_adb(["shell", "uiautomator", "dump", "/data/local/tmp/dump.xml"])
    subprocess.run([ADB_BIN, "-s", SERIAL, "pull", "/data/local/tmp/dump.xml", "/tmp/gallery_dump.xml"], capture_output=True, check=True)
    return ET.parse("/tmp/gallery_dump.xml").getroot()

def find_node(root, predicate):
    for node in root.iter():
        if predicate(node):
            return node
    return None

def tap_node(node):
    m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", node.attrib["bounds"])
    x = (int(m.group(1)) + int(m.group(3))) // 2
    y = (int(m.group(2)) + int(m.group(4))) // 2
    run_adb(["shell", "input", "tap", str(x), str(y)])

def tap_coords(x, y):
    run_adb(["shell", "input", "tap", str(x), str(y)])

def type_text(text):
    for word in text.split(" "):
        run_adb(["shell", "input", "text", word])
        run_adb(["shell", "input", "keyevent", "62"]) # SPACE
        time.sleep(0.3)

def create_synthetic_png(path, color_rgb):
    width, height = 300, 400
    raw_data = bytearray()
    for y in range(height):
        raw_data.append(0) # filter type
        for x in range(width):
            if (x + y) % 30 < 15:
                raw_data.extend(color_rgb)
            else:
                raw_data.extend([240, 240, 240])
    compressed = zlib.compress(raw_data)
    def chunk(tag, data):
        return struct.pack(">I", len(data)) + tag + data + struct.pack(">I", zlib.crc32(tag + data) & 0xffffffff)
    png = b"\x89PNG\r\n\x1a\n"
    ihdr = struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0)
    png += chunk(b"IHDR", ihdr)
    png += chunk(b"IDAT", compressed)
    png += chunk(b"IEND", b"")
    with open(path, "wb") as f:
        f.write(png)

def main():
    check_safety()
    
    # 0. Push synthetic images to emulator
    print("\n--- STEP 0: Generate and Push Synthetic Gallery Images ---")
    local_img_a = "/tmp/gallery_receipt_A.png"
    local_img_b = "/tmp/gallery_receipt_B.png"
    create_synthetic_png(local_img_a, [220, 50, 50])   # Red pattern
    create_synthetic_png(local_img_b, [50, 100, 220])  # Blue pattern
    
    subprocess.run([ADB_BIN, "-s", SERIAL, "push", local_img_a, "/sdcard/Pictures/gallery_receipt_A.png"], check=True)
    subprocess.run([ADB_BIN, "-s", SERIAL, "push", local_img_b, "/sdcard/Pictures/gallery_receipt_B.png"], check=True)
    run_adb(["shell", "am", "broadcast", "-a", "android.intent.action.MEDIA_SCANNER_SCAN_FILE", "-d", "file:///sdcard/Pictures/gallery_receipt_A.png"])
    run_adb(["shell", "am", "broadcast", "-a", "android.intent.action.MEDIA_SCANNER_SCAN_FILE", "-d", "file:///sdcard/Pictures/gallery_receipt_B.png"])
    print("Pushed gallery_receipt_A.png and gallery_receipt_B.png to /sdcard/Pictures/")

    # 1. Install final release APK and ensure fresh state
    print("\n--- STEP 1: Install Exact Final Release APK and Ensure Fresh State ---")
    assert os.path.exists(APK_PATH), f"APK not found at {APK_PATH}"
    install_res = run_adb(["install", "-r", "-t", APK_PATH])
    print(f"Install result: {install_res.stdout.strip()}")
    assert "Success" in install_res.stdout, f"Install failed: {install_res.stderr}"

    # Clear application data on emulator to guarantee fresh install state
    run_adb(["shell", "pm", "clear", PACKAGE])

    # Setup emulator environment
    run_adb(["shell", "settings", "put", "secure", "stylus_handwriting_enabled", "0"])
    run_adb(["shell", "settings", "put", "global", "stylus_handwriting_enabled", "0"])
    run_adb(["shell", "wm", "dismiss-keyguard"])

    # Launch app
    run_adb(["shell", "monkey", "-p", PACKAGE, "-c", "android.intent.category.LAUNCHER", "1"])
    time.sleep(3.0)
    take_screenshot("01_installed_dashboard.png")

    # 2. Create synthetic customer
    print("\n--- STEP 2: Create Synthetic Customer ---")
    root = dump_ui()
    add_cust_btn = find_node(root, lambda n: "Add Customer" in n.attrib.get("text", "") or "Add Person" in n.attrib.get("content-desc", ""))
    if add_cust_btn is None:
        add_cust_btn = find_node(root, lambda n: n.attrib.get("content-desc") == "Add Customer" or n.attrib.get("content-desc") == "Add Person" or n.attrib.get("content-desc") == "New Entry")
    
    assert add_cust_btn is not None, "Add Customer / New Entry button not found on dashboard"
    tap_node(add_cust_btn)
    time.sleep(1.5)

    root = dump_ui()
    # Check if Select Customer or Add New Customer dialog opened
    create_new = find_node(root, lambda n: "Create New Customer" in n.attrib.get("text", ""))
    if create_new is not None:
        # First type in search box
        search_input = find_node(root, lambda n: n.attrib.get("class") == "android.widget.EditText")
        if search_input is not None:
            tap_node(search_input)
            time.sleep(0.5)
            type_text("Gallery Verification Customer")
            time.sleep(0.8)
            root = dump_ui()
            create_new = find_node(root, lambda n: "Create New Customer" in n.attrib.get("text", ""))
        if create_new is not None:
            tap_node(create_new)
            time.sleep(1.5)

    root = dump_ui()
    # Now in Add New Customer dialog
    name_input = find_node(root, lambda n: n.attrib.get("class") == "android.widget.EditText")
    if name_input is not None:
        curr_text = name_input.attrib.get("text", "")
        if "Gallery" not in curr_text:
            tap_node(name_input)
            time.sleep(0.5)
            type_text("Gallery Verification Customer")
            time.sleep(0.5)

    root = dump_ui()
    create_btn = find_node(root, lambda n: n.attrib.get("text") == "Create" or n.attrib.get("text") == "Add Customer" or n.attrib.get("text") == "Save")
    assert create_btn is not None, "Create button in Add New Customer dialog not found"
    tap_node(create_btn)
    time.sleep(2.5)
    
    # Verify CustomerDetailScreen is displayed
    root = dump_ui()
    title = find_node(root, lambda n: "Gallery Verification Customer" in n.attrib.get("text", ""))
    assert title is not None, "CustomerDetailScreen not displayed for Gallery Verification Customer"
    print("Customer created and detail screen open.")

    # 3. Open Add Transaction Dialog
    print("\n--- STEP 3: Open Add Transaction Dialog ---")
    gave_btn = find_node(root, lambda n: "YOU GAVE" in n.attrib.get("text", ""))
    assert gave_btn is not None, "YOU GAVE button not found"
    tap_node(gave_btn)
    time.sleep(1.5)

    root = dump_ui()
    amt_input = find_node(root, lambda n: n.attrib.get("class") == "android.widget.EditText")
    assert amt_input is not None, "Amount field not found in transaction dialog"
    tap_node(amt_input)
    time.sleep(0.5)
    type_text("450.00")
    run_adb(["shell", "input", "keyevent", "111"]) # close keyboard
    time.sleep(0.8)

    # 4. Open Receipt Chooser
    print("\n--- STEP 4: Open Receipt Chooser Dialog ---")
    root = dump_ui()
    attach_btn = find_node(root, lambda n: "Attach Bill" in n.attrib.get("text", "") or "Bill" in n.attrib.get("text", ""))
    assert attach_btn is not None, "Attach Bill button not found in transaction dialog"
    tap_node(attach_btn)
    time.sleep(1.5)

    root = dump_ui()
    gallery_option = find_node(root, lambda n: "Choose from Gallery" in n.attrib.get("text", "") or "Gallery" in n.attrib.get("text", ""))
    assert gallery_option is not None, "Choose from Gallery option not found in chooser"
    take_screenshot("02_receipt_chooser.png")

    # 5. Tap Choose from Gallery -> Photo Picker
    print("\n--- STEP 5: Select Choose from Gallery ---")
    tap_node(gallery_option)
    time.sleep(2.5)

    # Confirm Android Photo Picker opens
    root = dump_ui()
    picker_node = find_node(root, lambda n: "photopicker" in n.attrib.get("package", "").lower() or "Photos" in n.attrib.get("content-desc", "") or "Media grid" in n.attrib.get("content-desc", ""))
    assert picker_node is not None, "Android Photo Picker did not open"
    take_screenshot("03_photo_picker_opened.png")
    print("Android Photo Picker successfully opened!")

    # 6. Select first synthetic receipt image
    print("\n--- STEP 6: Select Image from Photo Picker ---")
    first_photo = find_node(root, lambda n: "Photo taken" in n.attrib.get("content-desc", ""))
    assert first_photo is not None, "No photos found in photo picker grid"
    tap_node(first_photo)
    time.sleep(1.0)

    # If photo picker has a "Done" button, tap it
    root = dump_ui()
    done_btn = find_node(root, lambda n: n.attrib.get("text") == "Done")
    if done_btn is not None:
        tap_node(done_btn)
        time.sleep(2.5)

    # 7. Verify ReceiptCapturePreviewDialog appears
    print("\n--- STEP 7: Verify Receipt Confirmation Preview ---")
    root = dump_ui()
    choose_another_btn = find_node(root, lambda n: "Choose Another" in n.attrib.get("text", ""))
    assert choose_another_btn is not None, "ReceiptCapturePreviewDialog with 'Choose Another' did not appear"
    take_screenshot("04_preview_dialog.png")
    print("Preview dialog successfully verified!")

    # 8. Test 'Choose Another' action
    print("\n--- STEP 8: Test 'Choose Another' Flow ---")
    tap_node(choose_another_btn)
    time.sleep(2.5)

    # Confirm photo picker reopens
    root = dump_ui()
    picker_node = find_node(root, lambda n: "photopicker" in n.attrib.get("package", "").lower() or "Photos" in n.attrib.get("content-desc", "") or "Media grid" in n.attrib.get("content-desc", ""))
    assert picker_node is not None, "Photo picker did not reopen upon tapping 'Choose Another'"
    take_screenshot("05_choose_another_picker.png")
    print("Photo picker successfully reopened for 'Choose Another'!")

    # Select second image
    photos = [n for n in root.iter() if "Photo taken" in n.attrib.get("content-desc", "")]
    if len(photos) >= 2:
        second_photo = photos[1]
    else:
        second_photo = photos[0]
    tap_node(second_photo)
    time.sleep(1.0)

    root = dump_ui()
    done_btn = find_node(root, lambda n: n.attrib.get("text") == "Done")
    if done_btn is not None:
        tap_node(done_btn)
        time.sleep(2.5)

    # 9. Confirm and Save Receipt
    print("\n--- STEP 9: Confirm and Save Final Receipt ---")
    root = dump_ui()
    take_screenshot("06_final_preview.png")
    use_photo_btn = find_node(root, lambda n: "Use Photo" in n.attrib.get("text", ""))
    assert use_photo_btn is not None, "Use Photo button not found in preview dialog"
    tap_node(use_photo_btn)
    time.sleep(1.5)

    # Save transaction entry
    root = dump_ui()
    save_entry_btn = find_node(root, lambda n: "Save Entry" in n.attrib.get("text", "") or "Save" in n.attrib.get("text", ""))
    assert save_entry_btn is not None, "Save Entry button not found in transaction dialog"
    tap_node(save_entry_btn)
    time.sleep(2.5)

    # 10. Verify Thumbnail appears on transaction row
    print("\n--- STEP 10: Verify Thumbnail on Transaction Card ---")
    root = dump_ui()
    thumb = find_node(root, lambda n: n.attrib.get("content-desc") == "Bill (Tap to preview)")
    assert thumb is not None, "Receipt thumbnail not visible on transaction card"
    take_screenshot("07_saved_thumbnail.png")
    print("Receipt thumbnail successfully rendered on transaction card!")

    # 11. Open Full-Screen Image Viewer
    print("\n--- STEP 11: Open Full-Screen Image Viewer ---")
    tap_node(thumb)
    time.sleep(1.5)

    root = dump_ui()
    viewer_title = find_node(root, lambda n: "Bill Receipt" in n.attrib.get("text", ""))
    assert viewer_title is not None, "Fullscreen BillImagePreviewDialog did not open"
    take_screenshot("08_fullscreen_viewer.png")
    print("Fullscreen receipt viewer successfully verified!")

    # Dismiss viewer
    close_btn = find_node(root, lambda n: "Close" in n.attrib.get("text", ""))
    if close_btn is not None:
        tap_node(close_btn)
    else:
        run_adb(["shell", "input", "keyevent", "4"]) # back
    time.sleep(1.5)

    # 12. Force-stop and Reopen
    print("\n--- STEP 12: Force-stop and Cold Restart Persistence ---")
    run_adb(["shell", "am", "force-stop", PACKAGE])
    time.sleep(1.5)
    run_adb(["shell", "monkey", "-p", PACKAGE, "-c", "android.intent.category.LAUNCHER", "1"])
    time.sleep(3.0)

    # Navigate into customer detail if on dashboard
    root = dump_ui()
    cust_card = find_node(root, lambda n: "Gallery Verification Customer" in n.attrib.get("text", ""))
    if cust_card is not None:
        tap_node(cust_card)
        time.sleep(2.0)
        root = dump_ui()

    # 13. Verify Receipt still displays
    print("\n--- STEP 13: Verify Receipt Persistence Post-Restart ---")
    thumb_after = find_node(root, lambda n: n.attrib.get("content-desc") == "Bill (Tap to preview)")
    assert thumb_after is not None, "Receipt thumbnail did not persist across cold restart"
    take_screenshot("09_cold_restart_persistence.png")
    print("Receipt thumbnail persisted and verified across cold restart!")

    # Audit logcat for crashes/ANRs
    print("\n--- STEP 14: Logcat Crash/ANR Audit ---")
    log_res = run_adb(["logcat", "-d", "-t", "500"])
    fatal_matches = re.findall(r"FATAL EXCEPTION|ANR in com\.kb\.ledger", log_res.stdout)
    print(f"Logcat fatal occurrences: {len(fatal_matches)}")
    assert len(fatal_matches) == 0, f"Found crashes/ANRs in logcat: {fatal_matches}"

    print("\n=======================================================")
    print("GALLERY FLOW VERIFICATION: 100% SUCCESSFUL (ALL STEPS)")
    print("=======================================================")

if __name__ == "__main__":
    main()
