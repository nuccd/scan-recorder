#define WIN32_LEAN_AND_MEAN
#include <winsock2.h>
#include <windows.h>
#include <commdlg.h>
#include "protocol.hpp"
#include <algorithm>
#include <atomic>
#include <chrono>
#include <deque>
#include <sstream>
#include <stdexcept>
#include <thread>
#include <unordered_map>

namespace {
constexpr UINT message_update = WM_APP + 1;
constexpr UINT message_devices = WM_APP + 2;
constexpr UINT message_done = WM_APP + 3;
enum Control { path_id = 101, browse_id, devices_id, refresh_id, connect_id, enabled_id };
HWND root = nullptr, path_edit = nullptr, device_box = nullptr, status_text = nullptr;
HWND connect_button = nullptr, enabled_box = nullptr, count_text = nullptr;
HFONT font = nullptr;
std::atomic<bool> stopping{true}, enabled{false};
std::atomic<SOCKET> active_socket{INVALID_SOCKET};
std::atomic<unsigned long> submitted{0};
std::thread connection_thread, devices_thread;
std::wstring config_path;
struct DeviceList { std::vector<std::wstring> serials; std::wstring error; };

std::wstring widen(const std::string& text) {
  const int size = MultiByteToWideChar(CP_UTF8, 0, text.data(), static_cast<int>(text.size()), nullptr, 0);
  std::wstring result(size, L'\0');
  if (size) MultiByteToWideChar(CP_UTF8, 0, text.data(), static_cast<int>(text.size()), result.data(), size);
  return result;
}
void status(const std::wstring& text) {
  auto* copy = new std::wstring(text);
  if (!PostMessageW(root, message_update, 0, reinterpret_cast<LPARAM>(copy))) delete copy;
}
std::wstring text_of(HWND window) {
  const int length = GetWindowTextLengthW(window);
  std::wstring result(length + 1, L'\0');
  GetWindowTextW(window, result.data(), length + 1);
  result.resize(length);
  return result;
}
// Quote a single CreateProcess argument; never use cmd.exe or a command shell.
std::wstring quote(const std::wstring& argument) {
  std::wstring result = L"\"";
  std::size_t slashes = 0;
  for (wchar_t c : argument) {
    if (c == L'\\') { ++slashes; continue; }
    result.append(slashes * (c == L'"' ? 2 : 1), L'\\');
    slashes = 0;
    if (c == L'"') result.push_back(L'\\');
    result.push_back(c);
  }
  result.append(slashes * 2, L'\\');
  return result + L"\"";
}
std::string adb(const std::wstring& executable, const std::vector<std::wstring>& args) {
  SECURITY_ATTRIBUTES attributes{sizeof(attributes), nullptr, TRUE};
  HANDLE read_pipe = nullptr, write_pipe = nullptr;
  if (!CreatePipe(&read_pipe, &write_pipe, &attributes, 0)) throw std::runtime_error("pipe");
  SetHandleInformation(read_pipe, HANDLE_FLAG_INHERIT, 0);
  STARTUPINFOW startup{};
  startup.cb = sizeof(startup);
  startup.dwFlags = STARTF_USESTDHANDLES;
  startup.hStdOutput = startup.hStdError = write_pipe;
  HANDLE null_input = CreateFileW(L"NUL", GENERIC_READ, FILE_SHARE_READ | FILE_SHARE_WRITE,
    &attributes, OPEN_EXISTING, FILE_ATTRIBUTE_NORMAL, nullptr);
  startup.hStdInput = null_input;
  std::wstring command = quote(executable);
  for (const auto& argument : args) command += L" " + quote(argument);
  PROCESS_INFORMATION process{};
  const BOOL created = CreateProcessW(executable.c_str(), command.data(), nullptr, nullptr, TRUE,
    CREATE_NO_WINDOW, nullptr, nullptr, &startup, &process);
  const DWORD create_error = created ? 0 : GetLastError();
  if (null_input != INVALID_HANDLE_VALUE) CloseHandle(null_input);
  CloseHandle(write_pipe);
  if (!created) {
    CloseHandle(read_pipe); throw std::runtime_error("CreateProcess error " + std::to_string(create_error));
  }
  std::string output;
  const auto deadline = GetTickCount64() + 5000;
  bool finished = false;
  while (output.size() < 65536) {
    DWORD available = 0;
    if (!PeekNamedPipe(read_pipe, nullptr, 0, nullptr, &available, nullptr)) break;
    if (available) {
      char buffer[4096]; DWORD count = 0;
      if (ReadFile(read_pipe, buffer, std::min<DWORD>(available, sizeof(buffer)), &count, nullptr)) {
        output.append(buffer, count);
      }
    } else if (finished) break;
    else if (WaitForSingleObject(process.hProcess, 20) == WAIT_OBJECT_0) finished = true;
    if (GetTickCount64() >= deadline) break;
  }
  if (!finished && WaitForSingleObject(process.hProcess, 50) == WAIT_OBJECT_0) finished = true;
  DWORD code = 1;
  if (!finished) TerminateProcess(process.hProcess, 1);
  else GetExitCodeProcess(process.hProcess, &code);
  CloseHandle(read_pipe); CloseHandle(process.hThread); CloseHandle(process.hProcess);
  if (code != 0) throw std::runtime_error("ADB exit " + std::to_string(code));
  return output;
}
bool serial_valid(const std::string& serial) {
  return !serial.empty() && serial.size() <= 128 && std::all_of(serial.begin(), serial.end(), [](char c) {
    return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') ||
      c == '-' || c == '_' || c == '.';
  });
}
void refresh_devices() {
  if (devices_thread.joinable()) devices_thread.join();
  const auto path = text_of(path_edit);
  EnableWindow(GetDlgItem(root, refresh_id), FALSE);
  devices_thread = std::thread([path] {
    auto* devices = new DeviceList;
    try {
      std::istringstream lines(adb(path, {L"devices"}));
      std::string line;
      while (std::getline(lines, line)) {
        std::istringstream fields(line);
        std::string serial, state; fields >> serial >> state;
        if (state == "device" && serial_valid(serial)) devices->serials.push_back(widen(serial));
      }
    } catch (const std::exception& error) {
      devices->error = L"ADB 刷新失败，请检查路径和授权：" + widen(error.what());
    }
    if (!PostMessageW(root, message_devices, 0, reinterpret_cast<LPARAM>(devices))) delete devices;
  });
}
void write_line(SOCKET socket, const std::string& line) {
  const std::string data = line + "\n";
  std::size_t offset = 0;
  while (offset < data.size()) {
    const int count = send(socket, data.data() + offset, static_cast<int>(data.size() - offset), 0);
    if (count <= 0) throw std::runtime_error("disconnected");
    offset += count;
  }
}
class Lines {
  SOCKET socket_; std::string buffer_;
 public:
  explicit Lines(SOCKET socket) : socket_(socket) {}
  std::string read() {
    for (;;) {
      const auto newline = buffer_.find('\n');
      if (newline != std::string::npos) {
        const auto line = buffer_.substr(0, newline);
        buffer_.erase(0, newline + 1);
        return line;
      }
      if (buffer_.size() > scan::max_bytes * 2 + 64) throw std::runtime_error("oversized frame");
      char data[2048]; const int size = recv(socket_, data, sizeof(data), 0);
      if (size <= 0) throw std::runtime_error("disconnected");
      buffer_.append(data, size);
    }
  }
};
SOCKET connect_local(int port) {
  SOCKET socket = ::socket(AF_INET, SOCK_STREAM, IPPROTO_TCP);
  if (socket == INVALID_SOCKET) throw std::runtime_error("socket");
  DWORD timeout = 3500;
  BOOL no_delay = TRUE;
  setsockopt(socket, IPPROTO_TCP, TCP_NODELAY, reinterpret_cast<char*>(&no_delay), sizeof(no_delay));
  setsockopt(socket, SOL_SOCKET, SO_RCVTIMEO, reinterpret_cast<char*>(&timeout), sizeof(timeout));
  setsockopt(socket, SOL_SOCKET, SO_SNDTIMEO, reinterpret_cast<char*>(&timeout), sizeof(timeout));
  sockaddr_in address{};
  address.sin_family = AF_INET; address.sin_port = htons(static_cast<u_short>(port));
  address.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
  if (connect(socket, reinterpret_cast<sockaddr*>(&address), sizeof(address)) != 0) {
    closesocket(socket); throw std::runtime_error("connect");
  }
  return socket;
}
void receive(SOCKET socket, scan::DeliveryCache& results) {
  Lines lines(socket);
  if (lines.read() != "SCANRECORDER/1") throw std::runtime_error("protocol");
  write_line(socket, "READY/1");
  status(L"已连接手机。勾选“启用输入”，然后在 Excel 或其他软件中选好输入位置。");
  while (!stopping) {
    const auto line = lines.read();
    if (line == "PING") { write_line(socket, enabled ? "PONG ACTIVE" : "PONG PAUSED"); continue; }
    const auto frame = scan::decode(line);
    if (!frame) throw std::runtime_error("invalid frame");
    const auto [result, fresh] = results.submit(*frame, [](const auto& text) { return scan::type_text(text, enabled); });
    if (fresh && result == "OK") ++submitted;
    write_line(socket, "RESULT " + frame->id + " " + result);
    status(result == "OK" ? L"已提交键盘输入和 Enter；请以目标软件实际显示为准。" :
      L"本条未完整提交。请查看手机记录状态，核对电脑后再手动补发。");
  }
}
int forward(const std::wstring& path, const std::wstring& serial) {
  const auto output = adb(path, {L"-s", serial, L"forward", L"tcp:0", L"tcp:28461"});
  // adb may print daemon startup messages before the allocated port.
  std::istringstream lines(output); std::string line; int port = 0;
  while (std::getline(lines, line)) {
    if (!line.empty() && line.back() == '\r') line.pop_back();
    if (!line.empty() && std::all_of(line.begin(), line.end(), [](char c) { return c >= '0' && c <= '9'; })) {
      const auto parsed = std::stoul(line); if (parsed > 0 && parsed <= 65535) port = static_cast<int>(parsed);
    }
  }
  if (!port) throw std::runtime_error("forward");
  return port;
}
void connection(std::wstring path, std::wstring serial) {
  int port = 0, failures = 0;
  scan::DeliveryCache results;
  while (!stopping) {
    try {
      if (!port) port = forward(path, serial);
      const SOCKET socket = connect_local(port);
      active_socket = socket;
      try { receive(socket, results); } catch (...) { }
      active_socket = INVALID_SOCKET; closesocket(socket);
      failures = 0;
    } catch (...) { ++failures; }
    if (!stopping) {
      status(L"等待手机：检查 USB 调试授权，并在手机设置开启扫码枪模式、停留在扫码主页。");
      for (int i = 0; i < 10 && !stopping; ++i) std::this_thread::sleep_for(std::chrono::milliseconds(100));
      if (++failures >= 5 && port) {
        try { adb(path, {L"-s", serial, L"forward", L"--remove", L"tcp:" + std::to_wstring(port)}); } catch (...) { }
        port = 0; failures = 0;
      }
    }
  }
  if (port) {
    try { adb(path, {L"-s", serial, L"forward", L"--remove", L"tcp:" + std::to_wstring(port)}); } catch (...) { }
  }
  PostMessageW(root, message_done, 0, 0);
}
void stop() {
  enabled = false; stopping = true;
  SendMessageW(enabled_box, BM_SETCHECK, BST_UNCHECKED, 0);
  const SOCKET socket = active_socket.load();
  if (socket != INVALID_SOCKET) shutdown(socket, SD_BOTH);
}
HWND control(const wchar_t* kind, const wchar_t* label, DWORD style, int id, int x, int y, int width, int height) {
  HWND result = CreateWindowExW(0, kind, label, WS_CHILD | WS_VISIBLE | style, x, y, width, height,
    root, reinterpret_cast<HMENU>(static_cast<INT_PTR>(id)), GetModuleHandleW(nullptr), nullptr);
  SendMessageW(result, WM_SETFONT, reinterpret_cast<WPARAM>(font), TRUE);
  return result;
}
void create_controls() {
  font = CreateFontW(-17, 0, 0, 0, FW_NORMAL, FALSE, FALSE, FALSE, DEFAULT_CHARSET, OUT_DEFAULT_PRECIS,
    CLIP_DEFAULT_PRECIS, CLEARTYPE_QUALITY, DEFAULT_PITCH, L"Microsoft YaHei UI");
  control(L"STATIC", L"扫码记录 · USB 接收程序", 0, 0, 20, 16, 620, 28);
  control(L"STATIC", L"ADB 路径", 0, 0, 20, 56, 90, 24);
  path_edit = control(L"EDIT", L"", WS_BORDER | ES_AUTOHSCROLL | WS_TABSTOP, path_id, 110, 52, 435, 30);
  control(L"BUTTON", L"选择…", BS_PUSHBUTTON | WS_TABSTOP, browse_id, 555, 52, 85, 30);
  control(L"STATIC", L"手机设备", 0, 0, 20, 102, 90, 24);
  device_box = control(L"COMBOBOX", L"", CBS_DROPDOWNLIST | WS_VSCROLL | WS_TABSTOP,
    devices_id, 110, 98, 325, 160);
  control(L"BUTTON", L"刷新", BS_PUSHBUTTON | WS_TABSTOP, refresh_id, 445, 98, 85, 30);
  connect_button = control(L"BUTTON", L"连接", BS_PUSHBUTTON | WS_TABSTOP, connect_id, 540, 98, 100, 30);
  enabled_box = control(L"BUTTON", L"启用输入（当前输入框＋Enter）", BS_AUTOCHECKBOX | WS_TABSTOP,
    enabled_id, 20, 151, 620, 32);
  status_text = control(L"STATIC", L"选择 adb.exe 并刷新设备，连接后启用输入。", 0, 0, 20, 200, 620, 56);
  count_text = control(L"STATIC", L"本次已提交：0 条", 0, 0, 20, 264, 620, 26);
  control(L"STATIC", L"Excel：先将录入列设为“文本”，保留前导零和长编号。\r\n"
    L"手机确认后才输入；断线不自动补发，长按手机记录可补发。\r\n"
    L"退出本程序即停止接收。不会修改剪贴板或记录扫码原文。", 0, 0, 20, 302, 620, 78);
  wchar_t executable[32768]{}; GetModuleFileNameW(nullptr, executable, 32768);
  std::wstring folder(executable); folder.resize(folder.find_last_of(L"\\/") + 1);
  config_path = folder + L"receiver.ini";
  wchar_t stored[32768]{};
  GetPrivateProfileStringW(L"receiver", L"adb", (folder + L"adb.exe").c_str(), stored, 32768, config_path.c_str());
  SetWindowTextW(path_edit, stored);
}
void command(int id) {
  if (id == browse_id) {
    wchar_t file[32768]{};
    OPENFILENAMEW dialog{}; dialog.lStructSize = sizeof(dialog); dialog.hwndOwner = root;
    dialog.lpstrFile = file; dialog.nMaxFile = 32768;
    dialog.lpstrFilter = L"Android Debug Bridge (adb.exe)\0adb.exe\0Executable\0*.exe\0\0";
    dialog.Flags = OFN_FILEMUSTEXIST | OFN_PATHMUSTEXIST;
    if (GetOpenFileNameW(&dialog)) SetWindowTextW(path_edit, file);
  } else if (id == refresh_id) refresh_devices();
  else if (id == enabled_id) enabled = SendMessageW(enabled_box, BM_GETCHECK, 0, 0) == BST_CHECKED;
  else if (id == connect_id) {
    if (!stopping) {
      EnableWindow(connect_button, FALSE);
      stop(); SetWindowTextW(status_text, L"正在断开…"); return;
    }
    const auto index = SendMessageW(device_box, CB_GETCURSEL, 0, 0);
    if (index == CB_ERR) { SetWindowTextW(status_text, L"请先刷新并选择已授权的手机。"); return; }
    wchar_t serial[256]{}; SendMessageW(device_box, CB_GETLBTEXT, index, reinterpret_cast<LPARAM>(serial));
    if (connection_thread.joinable()) connection_thread.join();
    stopping = false;
    const auto path = text_of(path_edit);
    WritePrivateProfileStringW(L"receiver", L"adb", path.c_str(), config_path.c_str());
    SetWindowTextW(connect_button, L"断开");
    EnableWindow(GetDlgItem(root, refresh_id), FALSE); EnableWindow(GetDlgItem(root, browse_id), FALSE);
    EnableWindow(device_box, FALSE); EnableWindow(path_edit, FALSE);
    connection_thread = std::thread(connection, path, std::wstring(serial));
  }
}
LRESULT CALLBACK window_proc(HWND window, UINT message, WPARAM w, LPARAM l) {
  switch (message) {
    case WM_CREATE: root = window; create_controls(); return 0;
    case WM_COMMAND: command(LOWORD(w)); return 0;
    case message_update: {
      auto* text = reinterpret_cast<std::wstring*>(l);
      SetWindowTextW(status_text, text->c_str()); delete text;
      const auto count = L"本次已提交：" + std::to_wstring(submitted.load()) + L" 条";
      SetWindowTextW(count_text, count.c_str()); return 0;
    }
    case message_devices: {
      auto* devices = reinterpret_cast<DeviceList*>(l);
      SendMessageW(device_box, CB_RESETCONTENT, 0, 0);
      for (const auto& serial : devices->serials) SendMessageW(device_box, CB_ADDSTRING, 0, reinterpret_cast<LPARAM>(serial.c_str()));
      if (!devices->serials.empty()) SendMessageW(device_box, CB_SETCURSEL, 0, 0);
      const auto text = !devices->error.empty() ? devices->error : devices->serials.empty() ?
        L"没有已授权设备，请检查 USB 调试授权后刷新。" : L"已找到设备，请选择手机并连接。";
      SetWindowTextW(status_text, text.c_str());
      delete devices; EnableWindow(GetDlgItem(root, refresh_id), stopping); return 0;
    }
    case message_done:
      if (connection_thread.joinable()) connection_thread.join();
      EnableWindow(connect_button, TRUE);
      SetWindowTextW(connect_button, L"连接"); SetWindowTextW(status_text, L"已断开，输入已暂停。");
      EnableWindow(GetDlgItem(root, refresh_id), TRUE); EnableWindow(GetDlgItem(root, browse_id), TRUE);
      EnableWindow(device_box, TRUE); EnableWindow(path_edit, TRUE); return 0;
    case WM_DESTROY:
      stop();
      if (connection_thread.joinable()) connection_thread.join();
      if (devices_thread.joinable()) devices_thread.join();
      DeleteObject(font); PostQuitMessage(0); return 0;
    default: return DefWindowProcW(window, message, w, l);
  }
}
}
int WINAPI wWinMain(HINSTANCE instance, HINSTANCE, PWSTR, int show) {
  HANDLE mutex = CreateMutexW(nullptr, FALSE, L"Local\\ScanRecorderReceiverV1");
  if (!mutex || GetLastError() == ERROR_ALREADY_EXISTS) {
    MessageBoxW(nullptr, L"接收程序已在运行，请使用现有窗口。", L"扫码记录", MB_OK); return 1;
  }
  WSADATA data{}; if (WSAStartup(MAKEWORD(2, 2), &data)) return 2;
  SetProcessDPIAware();
  WNDCLASSW cls{}; cls.lpfnWndProc = window_proc; cls.hInstance = instance;
  cls.lpszClassName = L"ScanRecorderReceiver"; cls.hCursor = LoadCursorW(nullptr, IDC_ARROW);
  cls.hbrBackground = reinterpret_cast<HBRUSH>(COLOR_WINDOW + 1);
  RegisterClassW(&cls);
  HWND window = CreateWindowExW(0, cls.lpszClassName, L"扫码记录 · USB 接收程序 0.5.0",
    WS_OVERLAPPED | WS_CAPTION | WS_SYSMENU | WS_MINIMIZEBOX, CW_USEDEFAULT, CW_USEDEFAULT,
    680, 440, nullptr, nullptr, instance, nullptr);
  if (!window) return 3;
  ShowWindow(window, show); UpdateWindow(window);
  MSG message{};
  while (GetMessageW(&message, nullptr, 0, 0) > 0) {
    if (!IsDialogMessageW(window, &message)) { TranslateMessage(&message); DispatchMessageW(&message); }
  }
  WSACleanup(); CloseHandle(mutex); return static_cast<int>(message.wParam);
}
