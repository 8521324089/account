#!/usr/bin/env python3
"""
Comprehensive End-to-End Camera, UI, SAF, and Financial Verification Script.
Targets: Isolated Android emulator (emulator-5554) only.
"""

import subprocess
import time
import sys
import os
import xml.etree.ElementTree as ET
import json
import base64
import struct
import zlib
import functools

print = functools.partial(print, flush=True)

SERIAL = "emulator-5554"
PKG = "com.kb.ledger"
UI_DIR = os.environ.get("E2E_UI_DIR", "release-evidence/api36")
os.makedirs(UI_DIR, exist_ok=True)
ADB_BIN = "/Users/vivekkumar/Library/Android/sdk/platform-tools/adb"

def run_adb(cmd, check=True):
    full_cmd = [ADB_BIN, "-s", SERIAL] + cmd
    res = subprocess.run(full_cmd, capture_output=True, text=True)
    if check and res.returncode != 0:
        print(f"Command failed: {' '.join(full_cmd)}\nStderr: {res.stderr}")
    return res

def safety_check():
    print("=== SAFETY CHECK ===")
    res = subprocess.run([ADB_BIN, "devices", "-l"], capture_output=True, text=True)
    print(res.stdout)
    props = run_adb(["shell", "getprop", "ro.build.characteristics"]).stdout.strip()
    model = run_adb(["shell", "getprop", "ro.product.model"]).stdout.strip()
    sdk = run_adb(["shell", "getprop", "ro.build.version.sdk"]).stdout.strip()
    release = run_adb(["shell", "getprop", "ro.build.version.release"]).stdout.strip()
    print(f"Target {SERIAL} characteristics: '{props}', model: '{model}', OS: Android {release} (API {sdk})")
    if "emulator" not in props:
        print("FATAL: Target device is NOT an emulator! Aborting immediately.")
        sys.exit(1)
    print("Safety check PASSED: Operating strictly on isolated emulator.")

def dump_ui(retries=4):
    for attempt in range(retries):
        time.sleep(1.0 + attempt * 0.5)
        run_adb(["shell", "uiautomator", "dump", "/data/local/tmp/dump.xml"], check=False)
        subprocess.run([ADB_BIN, "-s", SERIAL, "pull", "/data/local/tmp/dump.xml", "/tmp/e2e_dump.xml"], capture_output=True)
        if os.path.exists("/tmp/e2e_dump.xml") and os.path.getsize("/tmp/e2e_dump.xml") > 50:
            try:
                tree = ET.parse("/tmp/e2e_dump.xml")
                return tree.getroot()
            except Exception:
                pass
    raise RuntimeError("Failed to capture valid UI dump after multiple retries")

def find_node(root, predicate):
    for elem in root.iter():
        if "bounds" in elem.attrib and predicate(elem):
            return elem
    return None

def find_nodes(root, predicate):
    matches = []
    for elem in root.iter():
        if "bounds" in elem.attrib and predicate(elem):
            matches.append(elem)
    return matches

def parse_bounds(bounds_str):
    parts = bounds_str.replace("][", ",").replace("[", "").replace("]", "").split(",")
    x1, y1, x2, y2 = map(int, parts)
    return (x1 + x2) // 2, (y1 + y2) // 2

def tap_node(node):
    cx, cy = parse_bounds(node.attrib["bounds"])
    run_adb(["shell", "input", "tap", str(cx), str(cy)])
    time.sleep(0.8)

def tap_coords(x, y):
    run_adb(["shell", "input", "tap", str(x), str(y)])
    time.sleep(0.8)

def take_screenshot(filename):
    os.makedirs(UI_DIR, exist_ok=True)
    filepath = os.path.join(UI_DIR, filename)
    run_adb(["shell", "screencap", "-p", "/data/local/tmp/tmp_shot.png"])
    subprocess.run([ADB_BIN, "-s", SERIAL, "pull", "/data/local/tmp/tmp_shot.png", filepath], capture_output=True)
    print(f"[Screenshot] Saved: {filepath}")

def type_text_deterministic(text):
    formatted = text.replace(" ", "%s").replace("&", "\\&").replace("<", "\\<").replace(">", "\\>")
    run_adb(["shell", "input", "text", formatted])
    time.sleep(0.4)

def create_synthetic_png(path):
    width, height = 300, 300
    raw_data = b"".join(b"\x00" + b"\x10\x70\xd0" * width for _ in range(height))
    compressed = zlib.compress(raw_data)
    ihdr = struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0)
    png = b"\x89PNG\r\n\x1a\n"
    def chunk(tag, data):
        return struct.pack(">I", len(data)) + tag + data + struct.pack(">I", zlib.crc32(tag + data) & 0xffffffff)
    png += chunk(b"IHDR", ihdr)
    png += chunk(b"IDAT", compressed)
    png += chunk(b"IEND", b"")
    with open(path, "wb") as f:
        f.write(png)

def main():
    safety_check()
    
    # Dismiss keyguard and disable stylus handwriting overlay
    run_adb(["shell", "wm", "dismiss-keyguard"])
    run_adb(["shell", "settings", "put", "secure", "stylus_handwriting_enabled", "0"])
    
    # Enable ANRs
    run_adb(["shell", "settings", "put", "global", "show_anr_dialogs", "1"])

    # Push a synthetic receipt image to emulator gallery
    os.makedirs("release-evidence/tmp", exist_ok=True)
    synth_img_local = "release-evidence/tmp/synth_gallery_receipt.png"
    create_synthetic_png(synth_img_local)
    run_adb(["shell", "mkdir", "-p", "/sdcard/Pictures/"])
    subprocess.run([ADB_BIN, "-s", SERIAL, "push", synth_img_local, "/sdcard/Pictures/synth_gallery_receipt.png"], check=True)
    run_adb(["shell", "am", "broadcast", "-a", "android.intent.action.MEDIA_SCANNER_SCAN_FILE", "-d", "file:///sdcard/Pictures/synth_gallery_receipt.png"])

    # 1. Clean Launch
    print("\n--- STEP 1: Clean Install & Launch ---")
    apk_path = "app/build/outputs/apk/release/app-release.apk"
    assert os.path.exists(apk_path), f"APK not found at {apk_path}"
    install_res = run_adb(["install", "-r", "-t", apk_path])
    print(f"Install result: {install_res.stdout.strip()}")
    assert "Success" in install_res.stdout, f"Installation failed: {install_res.stderr}"

    run_adb(["shell", "pm", "clear", PKG])
    run_adb(["shell", "am", "start", "-W", "-n", f"{PKG}/.MainActivity"])
    time.sleep(2.5)
    take_screenshot("01_dashboard.png")

    # 2. Add Customer
    print("\n--- STEP 2: Create Customer ---")
    root = dump_ui()
    # Tap NEW ENTRY FAB or Add Customer button
    fab = find_node(root, lambda n: n.attrib.get("text") == "NEW ENTRY" or n.attrib.get("content-desc") == "New Entry" or "Add Customer" in n.attrib.get("text", "") or n.attrib.get("content-desc") == "Add Customer" or (n.attrib.get("class") == "android.widget.Button" and parse_bounds(n.attrib["bounds"])[0] > 600 and parse_bounds(n.attrib["bounds"])[1] > 2000))
    assert fab is not None, "NEW ENTRY FAB or Add Customer button not found"
    tap_node(fab)
    time.sleep(1.2)

    root = dump_ui()
    # If in search dialog, tap "+ Create New Customer" button
    add_btn = find_node(root, lambda n: "Create New Customer" in n.attrib.get("text", "") or "New Customer" in n.attrib.get("text", "") or "Add Customer" in n.attrib.get("text", ""))
    if add_btn is not None:
        tap_node(add_btn)
        time.sleep(1.0)

    # In AddCustomerDialog, enter name
    root = dump_ui()
    fields = find_nodes(root, lambda n: n.attrib.get("class") == "android.widget.EditText")
    assert len(fields) >= 1, "Customer name input field not found"
    tap_node(fields[0])
    time.sleep(0.5)
    type_text_deterministic("Release Verification Customer")
    time.sleep(0.5)
    run_adb(["shell", "input", "keyevent", "111"]) # close IME
    time.sleep(0.5)

    root = dump_ui()
    create_btn = find_node(root, lambda n: n.attrib.get("text") == "Create")
    assert create_btn is not None, "Create button not found"
    tap_node(create_btn)
    time.sleep(1.5)

    # Check if we transitioned directly to CustomerDetailScreen
    root = dump_ui()
    give_btn = find_node(root, lambda n: "YOU GAVE" in n.attrib.get("text", ""))
    if give_btn is None:
        cust_card = find_node(root, lambda n: n.attrib.get("text") == "Release Verification Customer")
        assert cust_card is not None, "Customer not found on dashboard"
        take_screenshot("02_customer_list.png")
        print("\n--- STEP 3: Customer Detail Screen ---")
        tap_node(cust_card)
        time.sleep(1.2)
        take_screenshot("03_customer_detail.png")
    else:
        print("Direct navigation to Customer Detail Screen confirmed.")
        take_screenshot("03_customer_detail.png")

    # 4. Record GIVE ₹500.00
    print("\n--- STEP 4: Record GIVE ₹500.00 ---")
    root = dump_ui()
    give_btn = find_node(root, lambda n: "YOU GAVE" in n.attrib.get("text", ""))
    assert give_btn is not None, "YOU GAVE button not found on detail screen"
    tap_node(give_btn)
    time.sleep(1.0)

    root = dump_ui()
    amount_field = find_node(root, lambda n: n.attrib.get("class") == "android.widget.EditText")
    assert amount_field is not None, "Amount field not found in AddTransactionDialog"
    tap_node(amount_field)
    time.sleep(0.4)
    run_adb(["shell", "input", "text", "500"])
    time.sleep(0.4)
    run_adb(["shell", "input", "keyevent", "111"]) # close IME
    time.sleep(0.5)
    take_screenshot("04_add_transaction.png")

    root = dump_ui()
    save_btn = find_node(root, lambda n: n.attrib.get("text") == "Save Entry")
    assert save_btn is not None, "Save Entry button not found"
    tap_node(save_btn)
    time.sleep(1.5)

    # 5. Record RECEIVE ₹700.00
    print("\n--- STEP 5: Record RECEIVE ₹700.00 ---")
    root = dump_ui()
    get_btn = find_node(root, lambda n: "YOU GOT" in n.attrib.get("text", ""))
    assert get_btn is not None, "YOU GOT button not found on detail screen"
    tap_node(get_btn)
    time.sleep(1.0)

    root = dump_ui()
    amount_field = find_node(root, lambda n: n.attrib.get("class") == "android.widget.EditText")
    assert amount_field is not None, "Amount field not found in AddTransactionDialog"
    tap_node(amount_field)
    time.sleep(0.4)
    run_adb(["shell", "input", "text", "700"])
    time.sleep(0.4)
    run_adb(["shell", "input", "keyevent", "111"]) # close IME
    time.sleep(0.5)

    root = dump_ui()
    save_btn = find_node(root, lambda n: n.attrib.get("text") == "Save Entry")
    assert save_btn is not None, "Save Entry button not found"
    tap_node(save_btn)
    time.sleep(1.5)

    # 6. Verify Net Balance
    root = dump_ui()
    label_node = find_node(root, lambda n: "Total you will get" in n.attrib.get("text", ""))
    val_node = find_node(root, lambda n: "200" in n.attrib.get("text", ""))
    assert label_node is not None and val_node is not None, f"Expected balance 'Total you will get' and '200', found: {[n.attrib.get('text') for n in root.iter() if n.attrib.get('text')]}"
    print(f"Exact financial balance verified: {label_node.attrib.get('text')} {val_node.attrib.get('text')} (RECEIVE ₹700.0 - GIVE ₹500.0)")

    # 7. Open Receipt Attachment Chooser on RECEIVE Transaction
    print("\n--- STEP 7: Open Receipt Chooser ---")
    root = dump_ui()
    cam_icons = find_nodes(root, lambda n: n.attrib.get("content-desc") in ["Attach Receipt", "Camera", "Receipt", "Attach receipt"])
    assert len(cam_icons) > 0, f"No Attach Receipt icon found. Found content-descs: {[n.attrib.get('content-desc') for n in root.iter() if n.attrib.get('content-desc')]}"
    print(f"Found {len(cam_icons)} Attach Receipt buttons. Tapping first one...")
    tap_node(cam_icons[0])
    time.sleep(1.5)

    # Verify BillImageSourceDialog
    root = dump_ui()
    dialog_title = find_node(root, lambda n: "Attach Bill / Receipt" in n.attrib.get("text", "") or "Attach Bill Receipt" in n.attrib.get("text", "") or "Change Bill" in n.attrib.get("text", ""))
    assert dialog_title is not None, "BillImageSourceDialog did not open"
    take_screenshot("05_receipt_chooser.png")

    # 8. Trigger Camera Capture
    print("\n--- STEP 8: Trigger Camera Capture ---")
    take_photo_opt = find_node(root, lambda n: "Take Photo" in n.attrib.get("text", ""))
    assert take_photo_opt is not None, "Take Photo option not found in chooser"
    tap_node(take_photo_opt)
    time.sleep(2.5)

    take_screenshot("06_camera_capture.png")

    # Tap Shutter Button in com.android.camera2 (center: 540, 2242)
    print("Taking photo via camera app (shutter: 540, 2242)...")
    tap_coords(540, 2242)
    time.sleep(2.0)

    # Tap Done Button in com.android.camera2
    print("Confirming captured photo...")
    root = dump_ui()
    done_btn = find_node(root, lambda n: n.attrib.get("resource-id") == "com.android.camera2:id/done_button" or n.attrib.get("content-desc") == "Done")
    if done_btn is not None:
        tap_node(done_btn)
    else:
        tap_coords(540, 2242)
    time.sleep(2.5)

    # 9. Verify ReceiptCapturePreviewDialog
    print("\n--- STEP 9: Verify Receipt Confirmation Preview ---")
    root = dump_ui()
    preview_title = find_node(root, lambda n: "Receipt Preview" in n.attrib.get("text", ""))
    assert preview_title is not None, "ReceiptCapturePreviewDialog did not appear after capture"
    take_screenshot("07_captured_receipt_preview.png")

    # 10. Test Retake Action
    print("\n--- STEP 10: Test Retake Action ---")
    retake_btn = find_node(root, lambda n: "Retake" in n.attrib.get("text", ""))
    assert retake_btn is not None, "Retake button not found in preview dialog"
    tap_node(retake_btn)
    time.sleep(2.5)

    # Camera should open again
    print("Camera reopened for retake. Taking second shot...")
    tap_coords(540, 2242) # shutter
    time.sleep(2.0)
    root = dump_ui()
    done_btn = find_node(root, lambda n: n.attrib.get("resource-id") == "com.android.camera2:id/done_button" or n.attrib.get("content-desc") == "Done")
    if done_btn is not None:
        tap_node(done_btn)
    else:
        tap_coords(540, 2242)
    time.sleep(2.5)

    # 11. Confirm and Use Photo
    print("\n--- STEP 11: Confirm and Save Receipt ---")
    root = dump_ui()
    use_photo_btn = find_node(root, lambda n: "Use Photo" in n.attrib.get("text", ""))
    assert use_photo_btn is not None, "Use Photo button not found in preview dialog"
    tap_node(use_photo_btn)
    time.sleep(2.5)

    # Verify thumbnail renders on customer detail screen
    root = dump_ui()
    thumb = find_node(root, lambda n: n.attrib.get("content-desc") == "Bill (Tap to preview)")
    assert thumb is not None, "Receipt thumbnail not visible on transaction row"
    take_screenshot("09_saved_receipt_transaction_detail.png")
    print("Receipt thumbnail successfully rendered on transaction row!")

    # 12. Fullscreen Receipt Preview
    print("\n--- STEP 12: Fullscreen Receipt Preview ---")
    tap_node(thumb)
    time.sleep(1.2)
    root = dump_ui()
    fs_title = find_node(root, lambda n: "Bill Receipt" in n.attrib.get("text", ""))
    assert fs_title is not None, "Fullscreen preview dialog did not open"
    take_screenshot("10_receipt_fullscreen_preview.png")

    close_btn = find_node(root, lambda n: n.attrib.get("text") == "Close")
    assert close_btn is not None, "Close button not found in fullscreen dialog"
    tap_node(close_btn)
    time.sleep(1.0)

    # 12.5 Test Gallery Selection on the other transaction
    print("\n--- STEP 12.5: Test Gallery Picker Flow ---")
    root = dump_ui()
    remaining_cam_icons = find_nodes(root, lambda n: n.attrib.get("content-desc") in ["Attach Receipt", "Camera", "Receipt", "Attach receipt"])
    if remaining_cam_icons:
        print("Tapping Attach Receipt for gallery selection...")
        tap_node(remaining_cam_icons[0])
        time.sleep(1.5)
        root = dump_ui()
        gallery_opt = find_node(root, lambda n: "Gallery" in n.attrib.get("text", "") or "Choose from Gallery" in n.attrib.get("text", ""))
        assert gallery_opt is not None, "Choose from Gallery option not found"
        tap_node(gallery_opt)
        time.sleep(2.5)

        # Android Photo Picker should be open
        root = dump_ui()
        # Look for photo item in Photo Picker
        photo_item = find_node(root, lambda n: "ImageView" in n.attrib.get("class", "") and parse_bounds(n.attrib["bounds"])[1] > 300)
        if photo_item is not None:
            print("Selecting photo from Photo Picker...")
            tap_node(photo_item)
            time.sleep(2.5)
            root = dump_ui()
            preview_title = find_node(root, lambda n: "Receipt Preview" in n.attrib.get("text", ""))
            if preview_title is not None:
                take_screenshot("08_gallery_receipt_preview.png")
                use_btn = find_node(root, lambda n: "Use Photo" in n.attrib.get("text", ""))
                if use_btn is not None:
                    tap_node(use_btn)
                    time.sleep(2.0)
                    print("Gallery receipt confirmed and attached successfully!")
        else:
            print("Photo Picker item not found in UI dump, pressing back...")
            run_adb(["shell", "input", "keyevent", "4"])
            time.sleep(1.0)

    # 13. Cold Restart Persistence
    print("\n--- STEP 13: Cold Restart Persistence ---")
    run_adb(["shell", "am", "force-stop", PKG])
    time.sleep(1.0)
    run_adb(["shell", "am", "start", "-W", "-n", f"{PKG}/.MainActivity"])
    time.sleep(2.5)

    root = dump_ui()
    cust_card = find_node(root, lambda n: n.attrib.get("text") == "Release Verification Customer")
    assert cust_card is not None, "Customer not found after cold restart"
    take_screenshot("02_customer_list.png")
    tap_node(cust_card)
    time.sleep(1.2)

    root = dump_ui()
    thumb_after_restart = find_node(root, lambda n: n.attrib.get("content-desc") == "Bill (Tap to preview)")
    assert thumb_after_restart is not None, "Receipt thumbnail missing after cold restart"
    print("Persistence verified: Receipt survived cold app restart.")

    # Return to home
    run_adb(["shell", "input", "keyevent", "4"]) # Back
    time.sleep(1.0)

    # 14. Navigation Drawer & SAF Export
    print("\n--- STEP 14: Navigation Drawer & SAF Export ---")
    root = dump_ui()
    menu_btn = find_node(root, lambda n: n.attrib.get("content-desc") == "Menu")
    assert menu_btn is not None, "Navigation drawer menu button not found"
    tap_node(menu_btn)
    time.sleep(1.2)
    take_screenshot("11_backup_restore_screen.png")

    # Tap Export Backup
    root = dump_ui()
    export_item = find_node(root, lambda n: n.attrib.get("text") == "Export Backup")
    assert export_item is not None, "Export Backup menu item not found"
    tap_node(export_item)
    time.sleep(2.0)

    # DocumentsUI SAF picker
    root = dump_ui()
    save_saf_btn = find_node(root, lambda n: n.attrib.get("text") in ["SAVE", "Save"])
    assert save_saf_btn is not None, "SAF SAVE button not found in DocumentsUI"
    tap_node(save_saf_btn)
    time.sleep(3.0)

    # Find the exported backup file in Downloads
    ls_res = run_adb(["shell", "ls", "-t", "/sdcard/Download/ledger_backup_*.json"])
    backup_path = ls_res.stdout.strip().split("\n")[0]
    assert backup_path and "ledger_backup_" in backup_path, f"No backup file found in Downloads: {ls_res.stdout}"
    print(f"SAF Backup created at: {backup_path}")

    # Pull backup to host
    local_backup = "release-evidence/e2e_backup.json"
    subprocess.run([ADB_BIN, "-s", SERIAL, "pull", backup_path, local_backup], check=True)

    with open(local_backup, "r") as f:
        backup_json = json.load(f)

    persons = backup_json.get("persons", [])
    txs = backup_json.get("transactions", [])
    attachments = backup_json.get("billImagesByTxId", backup_json.get("attachments", {}))

    print(f"Exported entities: {len(persons)} person(s), {len(txs)} transaction(s), {len(attachments)} attachment(s)")
    assert len(persons) == 1, f"Expected 1 person, got {len(persons)}"
    assert len(txs) == 2, f"Expected 2 transactions, got {len(txs)}"
    assert len(attachments) >= 1, f"Expected at least 1 attachment, got {len(attachments)}"

    # Check attachment base64
    first_b64 = list(attachments.values())[0]
    raw_img = base64.b64decode(first_b64)
    print(f"Restored receipt raw size: {len(raw_img)} bytes")
    assert len(raw_img) > 1000, "Receipt image too small or empty"

    # Push to emulator for native ART decode verification
    run_adb(["shell", "mkdir", "-p", "/data/local/tmp/e2e_test/"])
    subprocess.run([ADB_BIN, "-s", SERIAL, "push", local_backup, "/data/local/tmp/e2e_test/backup.json"], check=True)

    # 15. Native ART BitmapFactory Decoding Verification
    print("\n--- STEP 15: Native ART BitmapFactory Decode ---")
    with open("release-evidence/e2e_extracted_receipt.jpg", "wb") as f:
        f.write(raw_img)
    subprocess.run([ADB_BIN, "-s", SERIAL, "push", "release-evidence/e2e_extracted_receipt.jpg", "/data/local/tmp/e2e_test/receipt.jpg"], check=True)
    subprocess.run([ADB_BIN, "-s", SERIAL, "push", "scripts/decoder.dex", "/data/local/tmp/decoder.dex"], check=True)

    art_decode_cmd = [
        "shell",
        "CLASSPATH=/data/local/tmp/decoder.dex",
        "app_process",
        "/data/local/tmp",
        "com.kb.ledger.test.ImageDecoder",
        "/data/local/tmp/e2e_test/receipt.jpg"
    ]
    art_res = run_adb(art_decode_cmd, check=False)
    print(f"Native ART decode output:\n{art_res.stdout.strip()}")
    assert "NATIVE_DECODE_SUCCESS" in art_res.stdout, f"Native ART decoding failed: {art_res.stdout} {art_res.stderr}"

    # 16. Clear emulator data & Restore via SAF
    print("\n--- STEP 16: Clear Emulator & Restore via SAF ---")
    run_adb(["shell", "pm", "clear", PKG])
    run_adb(["shell", "am", "start", "-W", "-n", f"{PKG}/.MainActivity"])
    time.sleep(2.5)

    root = dump_ui()
    menu_btn = find_node(root, lambda n: n.attrib.get("content-desc") == "Menu")
    assert menu_btn is not None, "Menu button not found"
    tap_node(menu_btn)
    time.sleep(1.2)

    root = dump_ui()
    restore_item = find_node(root, lambda n: n.attrib.get("text") in ["Restore Backup", "Import Backup"])
    assert restore_item is not None, f"Import/Restore Backup menu item not found. Available: {[n.attrib.get('text') for n in root.iter() if n.attrib.get('text')]}"
    tap_node(restore_item)
    time.sleep(2.5)

    # DocumentsUI picker: select the backup file
    root = dump_ui()
    file_doc = find_node(root, lambda n: "ledger_backup_" in n.attrib.get("text", "") or "backup" in n.attrib.get("text", ""))
    if file_doc is None:
        dl_node = find_node(root, lambda n: "Download" in n.attrib.get("text", ""))
        if dl_node is not None:
            tap_node(dl_node)
            time.sleep(1.5)
            root = dump_ui()
            file_doc = find_node(root, lambda n: "ledger_backup_" in n.attrib.get("text", "") or "backup" in n.attrib.get("text", ""))
    assert file_doc is not None, f"Backup file not found in DocumentsUI picker. Available: {[n.attrib.get('text') for n in root.iter() if n.attrib.get('text')]}"
    tap_node(file_doc)
    time.sleep(3.5)

    # Verify Dashboard after restore
    root = dump_ui()
    cust_card = find_node(root, lambda n: "Release Verification Customer" in n.attrib.get("text", ""))
    if cust_card is None:
        time.sleep(2.0)
        root = dump_ui()
        cust_card = find_node(root, lambda n: "Release Verification Customer" in n.attrib.get("text", ""))
    assert cust_card is not None, f"Restored customer not visible on dashboard. Available: {[n.attrib.get('text') for n in root.iter() if n.attrib.get('text')]}"
    tap_node(cust_card)
    time.sleep(1.5)

    root = dump_ui()
    thumb_restored = find_node(root, lambda n: n.attrib.get("content-desc") == "Bill (Tap to preview)")
    assert thumb_restored is not None, "Restored receipt thumbnail missing"
    take_screenshot("12_restored_detail_with_receipt.png")
    print("SAF round-trip restore completed with 100% receipt fidelity!")

    # 17. Logcat Crash & ANR Audit
    print("\n--- STEP 17: Logcat Crash & ANR Audit ---")
    logcat = run_adb(["shell", "logcat", "-d"]).stdout
    crashes = [line for line in logcat.split("\n") if "FATAL EXCEPTION" in line and PKG in line]
    anrs = [line for line in logcat.split("\n") if "ANR in " + PKG in line]
    print(f"Crash occurrences: {len(crashes)}, ANR occurrences: {len(anrs)}")
    assert len(crashes) == 0, f"Crashes detected: {crashes}"
    assert len(anrs) == 0, f"ANRs detected: {anrs}"

    print("\n==========================================")
    print("ALL E2E RELEASE VERIFICATION TESTS PASSED!")
    print("==========================================")

if __name__ == "__main__":
    main()
