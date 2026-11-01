#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <chrono>
#include <iostream>
#include <string>
#include <thread>
#include <vector>
#include "../src/adb_process.hpp"

namespace {
int checks = 0;
void check(bool pass, const char* description) {
  ++checks; if (!pass) throw std::runtime_error(description);
}
std::wstring text(HWND window) {
  wchar_t buffer[130000]{};
  DWORD_PTR result = 0;
  if (!SendMessageTimeoutW(window, WM_GETTEXT, 130000, reinterpret_cast<LPARAM>(buffer),
      SMTO_ABORTIFHUNG, 500, &result)) throw std::runtime_error("UI did not respond to WM_GETTEXT");
  return buffer;
}
HWND owned_window(const wchar_t* name, DWORD pid) {
  HWND window = FindWindowW(name, nullptr);
  DWORD owner = 0; GetWindowThreadProcessId(window, &owner);
  return owner == pid ? window : nullptr;
}
void click(HWND window, int id) {
  DWORD_PTR result = 0;
  if (!SendMessageTimeoutW(window, WM_COMMAND, MAKEWPARAM(id, BN_CLICKED),
      reinterpret_cast<LPARAM>(GetDlgItem(window, id)), SMTO_ABORTIFHUNG, 700, &result)) {
    throw std::runtime_error("UI command blocked");
  }
}
bool includes(HWND parent, const std::wstring& needle) {
  for (HWND child = GetWindow(parent, GW_CHILD); child; child = GetWindow(child, GW_HWNDNEXT)) {
    if (text(child).find(needle) != std::wstring::npos) return true;
  }
  return false;
}
}
int wmain(int argc, wchar_t** argv) {
  if (argc != 3 && argc != 4) { std::cerr << "receiver_ui_test receiver.exe fake-adb.exe [real-adb.exe]\n"; return 2; }
  try {
    std::vector<std::wstring> scenarios{L"hang", L"cancel", L"failure", L"unauthorized", L"success"};
    if (argc == 4) scenarios.push_back(L"real");
    for (const auto& scenario : scenarios) {
      SetEnvironmentVariableW(L"SCAN_RECORDER_ADB_FIXTURE", scenario == L"cancel" ? L"hang" : scenario.c_str());
      STARTUPINFOW startup{}; startup.cb = sizeof(startup);
      startup.dwFlags = STARTF_USESHOWWINDOW; startup.wShowWindow = SW_HIDE;
      PROCESS_INFORMATION process{};
      auto command = scan::quote(argv[1]);
      check(CreateProcessW(argv[1], command.data(), nullptr, nullptr, FALSE, 0, nullptr, nullptr,
        &startup, &process), "launch owned receiver");
      scan::Handle process_handle(process.hProcess), thread_handle(process.hThread);
      HWND window = nullptr;
      struct Cleanup {
        HANDLE process; HWND& window;
        ~Cleanup() {
          if (window) PostMessageW(window, WM_CLOSE, 0, 0);
          if (WaitForSingleObject(process, 4000) == WAIT_TIMEOUT) TerminateProcess(process, 1);
        }
      } cleanup{process.hProcess, window};
      const auto ready = GetTickCount64() + 4000;
      while (!window && GetTickCount64() < ready) { window = owned_window(L"ScanRecorderReceiver", process.dwProcessId); Sleep(20); }
      check(window != nullptr, "owned main window exists");
      HWND input = GetDlgItem(window, 106);
      check(SendMessageW(input, BM_GETCHECK, 0, 0) == BST_CHECKED, "keyboard input defaults to enabled on each launch");
      SendMessageW(input, BM_CLICK, 0, 0);
      check(SendMessageW(input, BM_GETCHECK, 0, 0) == BST_UNCHECKED, "user can pause the default input before connecting");
      const auto* adb = scenario == L"real" ? argv[3] : argv[2];
      SendMessageW(GetDlgItem(window, 101), WM_SETTEXT, 0, reinterpret_cast<LPARAM>(adb));
      click(window, 107);
      HWND log = owned_window(L"ScanRecorderDiagnostics", process.dwProcessId);
      check(log != nullptr, "open live diagnostic window");
      click(window, 104);
      Sleep(120);
      if (scenario == L"cancel") {
        const auto closing = GetTickCount64();
        PostMessageW(window, WM_CLOSE, 0, 0);
        check(WaitForSingleObject(process.hProcess, 1500) == WAIT_OBJECT_0,
          "closing during a stalled ADB launch returns promptly");
        check(GetTickCount64() - closing < 1500, "shutdown does not wait for the command timeout");
        window = nullptr;
        std::cout << "GUI scenario passed: cancel\n";
        continue;
      }
      if (scenario == L"hang") {
        check(!IsWindowEnabled(GetDlgItem(window, 104)), "refresh disabled during work");
        Sleep(1300);
        const auto progress = text(GetDlgItem(log, 200));
        check(progress.find(L"CreateProcessW") != std::wstring::npos && progress.find(L"仍在等待") != std::wstring::npos,
          "live progress exists while ADB is stalled");
        check(includes(window, L"物理内存") && includes(log, L"物理内存"), "live resource statistics in both windows");
      }
      const auto deadline = GetTickCount64() + 13500;
      while (!IsWindowEnabled(GetDlgItem(window, 104)) && GetTickCount64() < deadline) {
        DWORD_PTR result = 0;
        check(SendMessageTimeoutW(window, WM_NULL, 0, 0, SMTO_ABORTIFHUNG, 250, &result), "UI responsive during refresh");
        Sleep(100);
      }
      check(IsWindowEnabled(GetDlgItem(window, 104)), "refresh button recovers after completion/failure");
      const auto diagnostic = text(GetDlgItem(log, 200));
      if (scenario == L"hang") {
        check(diagnostic.find(L"超时") != std::wstring::npos && diagnostic.find(L"PID=") != std::wstring::npos,
          "timeout details visible in log");
        check(includes(window, L"超时"), "timeout visible in main window");
      } else if (scenario == L"failure") {
        check(diagnostic.find(L"exit=7") != std::wstring::npos && diagnostic.find(L"detailed reason") != std::wstring::npos,
          "actual exit and error output visible");
      } else if (scenario == L"unauthorized") check(includes(window, L"尚未授权"), "authorization guidance visible");
      else if (scenario == L"success") check(SendMessageW(GetDlgItem(window, 103), CB_GETCOUNT, 0, 0) == 1,
        "successful discovery populates devices");
      else check(diagnostic.find(L"ADB 调用结束") != std::wstring::npos, "real ADB refresh completed");
      check(SendMessageW(GetDlgItem(window, 106), BM_GETCHECK, 0, 0) == BST_UNCHECKED,
        "input remains paused throughout discovery tests");
      std::cout << "GUI scenario passed: " << scan::utf8(scenario) << '\n';
    }
    std::cout << checks << " UI checks passed (poll counts included); no keyboard input generated\n";
  } catch (const std::exception& error) { std::cerr << error.what() << '\n'; return 1; }
  return 0;
}
