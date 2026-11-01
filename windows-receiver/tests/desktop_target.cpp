#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <chrono>
#include <fstream>
#include <iostream>
#include <string>
#include <thread>

namespace {
std::wstring received;
bool entered = false;
LRESULT CALLBACK target_proc(HWND window, UINT message, WPARAM w, LPARAM l) {
  if (message == WM_CHAR) {
    received.push_back(static_cast<wchar_t>(w));
    if (w == VK_RETURN) { entered = true; SetTimer(window, 1, 500, nullptr); }
    InvalidateRect(window, nullptr, TRUE); return 0;
  }
  if (message == WM_PAINT) {
    PAINTSTRUCT paint{}; HDC dc = BeginPaint(window, &paint);
    const auto text = L"USB keyboard integration test (owned window)\n" + received;
    RECT bounds{15, 15, 1000, 180}; DrawTextW(dc, text.c_str(), -1, &bounds, DT_LEFT);
    EndPaint(window, &paint); return 0;
  }
  if (message == WM_TIMER) { PostQuitMessage(0); return 0; }
  return DefWindowProcW(window, message, w, l);
}
void pump() {
  MSG message{};
  while (PeekMessageW(&message, nullptr, 0, 0, PM_REMOVE)) {
    TranslateMessage(&message); DispatchMessageW(&message);
  }
}
}
int wmain(int argc, wchar_t** argv) {
  if (argc != 4 && argc != 5) {
    std::cerr << "usage: desktop_target receiverPID adbPath deviceSerial [transport-only]\n"; return 2;
  }
  const DWORD expected_pid = std::stoul(argv[1]);
  HWND receiver = FindWindowW(L"ScanRecorderReceiver", nullptr);
  DWORD actual_pid = 0; GetWindowThreadProcessId(receiver, &actual_pid);
  if (!receiver || actual_pid != expected_pid) { std::cerr << "owned receiver not found\n"; return 3; }
  struct Cleanup {
    HWND receiver;
    ~Cleanup() { PostMessageW(receiver, WM_CLOSE, 0, 0); }
  } cleanup{receiver};
  // Pause the new default before discovering or connecting any phone.
  SendMessageW(GetDlgItem(receiver, 106), BM_SETCHECK, BST_UNCHECKED, 0);
  SendMessageW(receiver, WM_COMMAND, MAKEWPARAM(106, BN_CLICKED), reinterpret_cast<LPARAM>(GetDlgItem(receiver, 106)));
  ShowWindow(receiver, SW_SHOWNOACTIVATE);
  UpdateWindow(receiver);
  SetWindowTextW(GetDlgItem(receiver, 101), argv[2]);
  SendMessageW(receiver, WM_COMMAND, MAKEWPARAM(104, BN_CLICKED), reinterpret_cast<LPARAM>(GetDlgItem(receiver, 104)));
  HWND devices = GetDlgItem(receiver, 103);
  const auto deadline = GetTickCount64() + 6000;
  LRESULT index = CB_ERR;
  while (GetTickCount64() < deadline && index == CB_ERR) {
    index = SendMessageW(devices, CB_FINDSTRINGEXACT, static_cast<WPARAM>(-1), reinterpret_cast<LPARAM>(argv[3]));
    std::this_thread::sleep_for(std::chrono::milliseconds(30));
  }
  if (index == CB_ERR) { std::cerr << "requested ADB device not found\n"; return 4; }
  SendMessageW(devices, CB_SETCURSEL, index, 0);
  SendMessageW(receiver, WM_COMMAND, MAKEWPARAM(105, BN_CLICKED), reinterpret_cast<LPARAM>(GetDlgItem(receiver, 105)));
  if (argc == 5 && std::wstring(argv[4]) == L"transport-only") {
    std::this_thread::sleep_for(std::chrono::seconds(7));
    std::cout << "Transport-only fixture finished with input disabled; check Android assertion result\n";
    return 0;
  }
  WNDCLASSW cls{}; cls.lpfnWndProc = target_proc; cls.hInstance = GetModuleHandleW(nullptr);
  cls.lpszClassName = L"ScanRecorderOwnedTestTarget"; cls.hbrBackground = reinterpret_cast<HBRUSH>(COLOR_WINDOW + 1);
  RegisterClassW(&cls);
  HWND target = CreateWindowExW(WS_EX_TOPMOST, cls.lpszClassName, L"扫码枪输入验证（仅测试窗口）",
    WS_OVERLAPPEDWINDOW, 80, 80, 1000, 200, nullptr, nullptr, cls.hInstance, nullptr);
  ShowWindow(target, SW_SHOWNORMAL); SetForegroundWindow(target); SetFocus(target);
  const DWORD foreground_thread = GetWindowThreadProcessId(GetForegroundWindow(), nullptr);
  const DWORD own_thread = GetCurrentThreadId();
  if (GetForegroundWindow() != target && foreground_thread && foreground_thread != own_thread) {
    if (AttachThreadInput(own_thread, foreground_thread, TRUE)) {
      BringWindowToTop(target); SetForegroundWindow(target); SetFocus(target);
      AttachThreadInput(own_thread, foreground_thread, FALSE);
    }
  }
  pump(); std::this_thread::sleep_for(std::chrono::milliseconds(200));
  if (GetForegroundWindow() != target) {
    DestroyWindow(target); std::cerr << "SKIP: cannot obtain foreground for owned test target\n"; return 77;
  }
  // Enable only after our own target owns focus. The receiver never steals it.
  SendMessageW(GetDlgItem(receiver, 106), BM_SETCHECK, BST_CHECKED, 0);
  SendMessageW(receiver, WM_COMMAND, MAKEWPARAM(106, BN_CLICKED), reinterpret_cast<LPARAM>(GetDlgItem(receiver, 106)));
  const auto end = GetTickCount64() + 18000;
  while (GetTickCount64() < end && !entered) { pump(); std::this_thread::sleep_for(std::chrono::milliseconds(10)); }
  std::this_thread::sleep_for(std::chrono::milliseconds(600));
  const bool pass = received == L"001234567890123456中文\U0001f600\r";
  SendMessageW(GetDlgItem(receiver, 106), BM_SETCHECK, BST_UNCHECKED, 0);
  SendMessageW(receiver, WM_COMMAND, 106, 0);
  DestroyWindow(target);
  std::cout << (pass ? "PASS" : "FAIL") << ": owned target received original UTF16 text and exactly one Enter; units="
    << received.size() << '\n';
  return pass ? 0 : 1;
}
