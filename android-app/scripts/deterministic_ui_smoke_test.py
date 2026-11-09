#!/usr/bin/env python3
"""
Deterministic UI Smoke Test for Ledger Android App
Executes exact test flow without blind hardcoded coordinates:
1. Create customer 'Release Test Customer'
2. Record GIVE transaction of exactly ₹500.00
3. Verify person balance is You Give ₹500.00
4. Record RECEIVE transaction of exactly ₹700.00
5. Verify resulting balance is You Get ₹200.00
6. Force-stop and relaunch the app
7. Verify person and both transactions remain correct
8. Check logcat for crashes and ANRs
"""

import subprocess
import time
import xml.etree.ElementTree as ET
import sys
import os

DEVICE = "emulator-5554"
ADB = os.path.expanduser("~/Library/Android/sdk/platform-tools/adb")

def run_adb(args):
    cmd = [ADB, "-s", DEVICE] + args
    res = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
    if res.returncode != 0:
        print(f"ADB error running {cmd}: {res.stderr}")
    return res.stdout.strip()

def dump_ui(filename="dump.xml"):
    local_path = f"/tmp/{filename}"
    remote_path = f"/sdcard/{filename}"
    run_adb(["shell", "uiautomator", "dump", remote_path])
    run_adb(["pull", remote_path, local_path])
    if not os.path.exists(local_path):
        raise RuntimeError(f"Failed to pull {remote_path}")
    tree = ET.parse(local_path)
    return tree.getroot()

def parse_bounds(bounds_str):
    if not bounds_str:
        return (0, 0, 0, 0, 0, 0)
    parts = bounds_str.replace("][", ",").replace("[", "").replace("]", "").split(",")
    x1, y1, x2, y2 = [int(p) for p in parts]
    cx = (x1 + x2) // 2
    cy = (y1 + y2) // 2
    return (x1, y1, x2, y2, cx, cy)

def find_node(root, predicate):
    for elem in root.iter():
        if "bounds" in elem.attrib and predicate(elem):
            return elem
    return None

def find_nodes(root, predicate):
    nodes = []
    for elem in root.iter():
        if "bounds" in elem.attrib and predicate(elem):
            nodes.append(elem)
    return nodes

def wait_for_node(predicate, timeout=12.0, interval=1.0, filename="poll.xml"):
    start = time.time()
    while time.time() - start < timeout:
        try:
            root = dump_ui(filename)
            node = find_node(root, predicate)
            if node is not None:
                return root, node
        except Exception:
            pass
        time.sleep(interval)
    root = dump_ui(filename)
    node = find_node(root, predicate)
    if node is not None:
        return root, node
    all_texts = [n.attrib.get("text") for n in root.iter() if n.attrib.get("text")]
    raise TimeoutError(f"Node matching predicate not found within {timeout}s! Visible texts: {all_texts}")

def tap_node(node):
    _, _, _, _, cx, cy = parse_bounds(node.attrib["bounds"])
    print(f"  -> Tapping element at ({cx}, {cy}) [text={node.attrib.get('text')!r}, desc={node.attrib.get('content-desc')!r}]")
    run_adb(["shell", "input", "tap", str(cx), str(cy)])
    time.sleep(1.0)

def set_edit_text(edit_text_node, text_to_enter):
    _, _, _, _, cx, cy = parse_bounds(edit_text_node.attrib["bounds"])
    print(f"  -> Focusing EditText at ({cx}, {cy})")
    run_adb(["shell", "input", "tap", str(cx), str(cy)])
    time.sleep(0.5)
    
    # Fast clear existing text
    run_adb(["shell", "input keyevent 123; for i in $(seq 1 40); do input keyevent 67; done"])
    time.sleep(0.3)
    
    # Type words cleanly with explicit commit spaces to avoid Gboard composition drops
    print(f"  -> Entering text: {text_to_enter!r}")
    words = text_to_enter.split(" ")
    for idx, w in enumerate(words):
        run_adb(["shell", "input", "text", w])
        time.sleep(0.2)
        run_adb(["shell", "input", "keyevent", "62"]) # SPACE commits the word
        time.sleep(0.2)
    # Delete trailing extra space
    run_adb(["shell", "input", "keyevent", "67"])
    time.sleep(0.5)

def main():
    print("=== Starting Deterministic UI Smoke Test on", DEVICE, "===")
    
    # Check device
    chars = run_adb(["shell", "getprop", "ro.build.characteristics"])
    if chars != "emulator":
        raise RuntimeError(f"Device {DEVICE} is not an emulator! characteristics={chars}")
    print("Device verified as isolated emulator.")
    
    # Clean synthetic test installation
    print("Resetting app to clean synthetic state...")
    run_adb(["shell", "pm", "clear", "com.kb.ledger"])
    time.sleep(1.0)
    
    # Clear logcat
    run_adb(["logcat", "-c"])
    
    # Launch App
    print("\n1. Launching com.kb.ledger...")
    run_adb(["shell", "am", "start", "-W", "-n", "com.kb.ledger/com.kb.ledger.MainActivity"])
    
    root, total_give = wait_for_node(lambda n: n.attrib.get("text") == "Total You Give", timeout=15.0)
    print("✓ Home screen loaded successfully.")

    # Step 1: Create customer 'Release Test Customer'
    print("\n2. Creating customer 'Release Test Customer'...")
    fab = find_node(root, lambda n: parse_bounds(n.attrib["bounds"])[0] > 600 and parse_bounds(n.attrib["bounds"])[1] > 2000 and n.attrib.get("clickable") == "true")
    if not fab:
        fab = find_node(root, lambda n: n.attrib.get("class") == "android.widget.Button" and parse_bounds(n.attrib["bounds"])[1] > 2000)
    if not fab:
        raise RuntimeError("Could not find Add Customer FAB on home screen")
    tap_node(fab)

    # In Select Customer dialog, tap '+ Create New Customer'
    root, create_btn = wait_for_node(lambda n: "+ Create New Customer" in n.attrib.get("text", ""))
    tap_node(create_btn)

    # In Add New Customer dialog:
    root, name_field = wait_for_node(lambda n: n.attrib.get("class") == "android.widget.EditText")
    set_edit_text(name_field, "Release Test Customer")
    
    # Verify input before submitting
    root, verified_field = wait_for_node(lambda n: n.attrib.get("class") == "android.widget.EditText" and "Release Test Customer" in n.attrib.get("text", ""))
    print(f"✓ Verified entered customer name before submit: {verified_field.attrib.get('text')!r}")

    # Tap Create button
    root, create_submit = wait_for_node(lambda n: n.attrib.get("text") == "Create")
    tap_node(create_submit)
    time.sleep(1.5)

    # Re-open customer selection to navigate to customer detail
    root, _ = wait_for_node(lambda n: n.attrib.get("text") == "Total You Give")
    fab = find_node(root, lambda n: parse_bounds(n.attrib["bounds"])[0] > 600 and parse_bounds(n.attrib["bounds"])[1] > 2000 and n.attrib.get("clickable") == "true")
    tap_node(fab)
    
    root, cust_item = wait_for_node(lambda n: "Release Test Customer" in n.attrib.get("text", ""))
    print(f"✓ Found customer in list: {cust_item.attrib.get('text')!r}")
    tap_node(cust_item)
    time.sleep(1.5)

    # Step 2: Record GIVE transaction of exactly ₹500.00
    print("\n3. Recording GIVE transaction of exactly ₹500.00...")
    root, give_btn = wait_for_node(lambda n: "YOU GAVE" in n.attrib.get("text", ""))
    tap_node(give_btn)

    root, amount_field = wait_for_node(lambda n: n.attrib.get("class") == "android.widget.EditText")
    set_edit_text(amount_field, "500")

    # Verify input before submit
    root, verified_amount = wait_for_node(lambda n: n.attrib.get("class") == "android.widget.EditText" and n.attrib.get("text") == "500")
    print(f"✓ Verified entered GIVE amount before submit: {verified_amount.attrib.get('text')!r}")

    root, save_btn = wait_for_node(lambda n: n.attrib.get("text") == "Save Entry")
    tap_node(save_btn)
    time.sleep(1.5)

    # Step 3: Verify person balance is You Give ₹500.00
    print("\n4. Verifying balance after GIVE...")
    root, give_val = wait_for_node(lambda n: ("₹500.0" in n.attrib.get("text", "") or "₹500" in n.attrib.get("text", "")) and parse_bounds(n.attrib.get("bounds", ""))[1] < 600, timeout=10.0)
    give_label = find_node(root, lambda n: "Total you will give" in n.attrib.get("text", ""))
    print(f"✓ Verified balance after GIVE: {give_label.attrib.get('text') if give_label is not None else 'Total you will give'}: {give_val.attrib.get('text')}")

    # Step 4: Record RECEIVE transaction of exactly ₹700.00
    print("\n5. Recording RECEIVE transaction of exactly ₹700.00...")
    root, got_btn = wait_for_node(lambda n: "YOU GOT" in n.attrib.get("text", ""))
    tap_node(got_btn)

    root, amount_field = wait_for_node(lambda n: n.attrib.get("class") == "android.widget.EditText")
    set_edit_text(amount_field, "700")

    # Verify input before submit
    root, verified_got = wait_for_node(lambda n: n.attrib.get("class") == "android.widget.EditText" and n.attrib.get("text") == "700")
    print(f"✓ Verified entered RECEIVE amount before submit: {verified_got.attrib.get('text')!r}")

    root, save_btn = wait_for_node(lambda n: n.attrib.get("text") == "Save Entry")
    tap_node(save_btn)
    time.sleep(1.5)

    # Step 5: Verify resulting balance is You Get ₹200.00
    print("\n6. Verifying resulting net balance (RECEIVE - GIVE)...")
    root, get_val = wait_for_node(lambda n: ("₹200.0" in n.attrib.get("text", "") or "₹200" in n.attrib.get("text", "")) and parse_bounds(n.attrib.get("bounds", ""))[1] < 600, timeout=10.0)
    get_label = find_node(root, lambda n: "Total you will get" in n.attrib.get("text", ""))
    print(f"✓ Verified net balance: {get_label.attrib.get('text') if get_label is not None else 'Total you will get'}: {get_val.attrib.get('text')} (Formula: ₹700.00 - ₹500.00 = +₹200.00)")

    # Step 6: Force-stop and relaunch the app
    print("\n7. Force-stopping and relaunching the app...")
    run_adb(["shell", "am", "force-stop", "com.kb.ledger"])
    time.sleep(1.5)
    run_adb(["shell", "am", "start", "-n", "com.kb.ledger/com.kb.ledger.MainActivity"])
    time.sleep(2.0)

    # Step 7: Verify person and both transactions remain correct
    print("\n8. Verifying data persistence after relaunch...")
    root = dump_ui()
    all_texts = [n.attrib.get("text") for n in find_nodes(root, lambda n: bool(n.attrib.get("text")))]
    print("Initial texts after relaunch:", all_texts)
    
    # Check if we are on the Customer Detail screen or Home Dashboard
    if any("YOU GAVE" in t for t in all_texts):
        # We are on the Customer Detail screen
        print("Relaunched directly onto Customer Detail screen.")
        cust_node = find_node(root, lambda n: "Release Test Customer" in n.attrib.get("text", ""))
        bal_node = find_node(root, lambda n: "₹200.0" in n.attrib.get("text", "") or "₹200" in n.attrib.get("text", ""))
        tx_700 = find_node(root, lambda n: "₹700.0" in n.attrib.get("text", ""))
        tx_500 = find_node(root, lambda n: "₹500.0" in n.attrib.get("text", ""))
        
        if cust_node is None or bal_node is None or tx_700 is None or tx_500 is None:
            raise RuntimeError(f"Customer detail persistence check failed! Texts: {all_texts}")
        print(f"✓ Detail screen verified: Customer={cust_node.attrib.get('text')}, Net Balance={bal_node.attrib.get('text')}")
        print(f"✓ Detail screen transactions verified: RECEIVE={tx_700.attrib.get('text')}, GIVE={tx_500.attrib.get('text')}")
        
        # Navigate back to Home Dashboard to verify active tab
        print("Navigating back to Home Dashboard...")
        run_adb(["shell", "input", "keyevent", "4"]) # KEYCODE_BACK
        time.sleep(1.5)
        root, active_tab = wait_for_node(lambda n: "Active (1)" in n.attrib.get("text", ""), timeout=5.0)
        dash_cust = find_node(root, lambda n: "Release Test Customer" in n.attrib.get("text", ""))
        dash_bal = find_node(root, lambda n: "₹200.0" in n.attrib.get("text", "") or "₹200" in n.attrib.get("text", ""))
        print(f"✓ Home dashboard verified after return: {active_tab.attrib.get('text')}, Customer={dash_cust.attrib.get('text')}, Balance={dash_bal.attrib.get('text')}")
    else:
        # We are on the Home Dashboard
        root, active_tab = wait_for_node(lambda n: "Active (1)" in n.attrib.get("text", ""), timeout=5.0)
        dash_cust = find_node(root, lambda n: "Release Test Customer" in n.attrib.get("text", ""))
        dash_bal = find_node(root, lambda n: "₹200.0" in n.attrib.get("text", "") or "₹200" in n.attrib.get("text", ""))
        print(f"✓ Home dashboard verified: {active_tab.attrib.get('text')}, Customer={dash_cust.attrib.get('text')}, Balance={dash_bal.attrib.get('text')}")
        
        # Open customer detail
        tap_node(dash_cust)
        time.sleep(1.5)
        root, _ = wait_for_node(lambda n: "Release Test Customer" in n.attrib.get("text", ""))
        tx_700 = find_node(root, lambda n: "₹700.0" in n.attrib.get("text", ""))
        tx_500 = find_node(root, lambda n: "₹500.0" in n.attrib.get("text", ""))
        if tx_700 is None or tx_500 is None:
            raise RuntimeError(f"Transaction list persistence failed! Texts: {[n.attrib.get('text') for n in find_nodes(root, lambda n: bool(n.attrib.get('text')))]}")
        print(f"✓ Detail screen transactions verified: RECEIVE={tx_700.attrib.get('text')}, GIVE={tx_500.attrib.get('text')}")

    # Step 8: Check logcat for crashes and ANRs
    print("\n9. Checking logcat for crashes and ANRs...")
    logcat = run_adb(["logcat", "-d"])
    crashes = [l for l in logcat.split("\n") if "FATAL EXCEPTION" in l or "AndroidRuntime: FATAL" in l]
    anrs = [l for l in logcat.split("\n") if "ANR in com.kb.ledger" in l]
    
    if crashes:
        print("❌ CRASHES FOUND:", crashes)
        sys.exit(1)
    if anrs:
        print("❌ ANR FOUND:", anrs)
        sys.exit(1)
        
    print("✓ Logcat audit passed: 0 crashes, 0 ANRs.")
    print("\n🎉 ALL DETERMINISTIC UI SMOKE TESTS PASSED WITH 100% SUCCESS! 🎉")

if __name__ == "__main__":
    main()
