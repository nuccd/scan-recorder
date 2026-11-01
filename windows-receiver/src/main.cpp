#define WIN32_LEAN_AND_MEAN
#include <winsock2.h>
#include <windows.h>
#include <commdlg.h>
#include "protocol.hpp"
#include "diagnostics.hpp"
#include "device_search.hpp"
#include "resources.hpp"
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
constexpr UINT message_log = WM_APP + 4;
enum Control { path_id = 101, browse_id, devices_id, refresh_id, connect_id, enabled_id, log_id };
HWND root = nullptr, path_edit = nullptr, device_box = nullptr, status_text = nullptr;
HWND connect_button = nullptr, enabled_box = nullptr, count_text = nullptr;
HWND log_window = nullptr, log_edit = nullptr;
HWND resource_text = nullptr, log_resource_text = nullptr;
HFONT font = nullptr;
std::atomic<bool> stopping{true}, enabled{false};
std::atomic<bool> closing{false}, searching{false};
std::atomic<SOCKET> active_socket{INVALID_SOCKET};
std::atomic<unsigned long> submitted{0};
std::thread connection_thread, devices_thread;
std::wstring config_path;
auto diagnostic = std::make_shared<scan::DiagnosticLog>();
scan::AdbRunner adb_runner([log = diagnostic](const auto& entry) { log->write(entry); });
using scan::DeviceList;
scan::ResourceMonitor resource_monitor;
scan::ResourceSample resource_sample;
unsigned resource_ticks = 0;

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
std::string adb(const std::wstring& executable, const std::vector<std::wstring>& args,
    DWORD timeout_ms = 5000, const std::atomic<bool>* cancelled = nullptr) {
  return adb_runner.run(executable, args, timeout_ms, cancelled);
}
void refresh_devices() {
  if (!stopping || searching.exchange(true)) return;
  if (devices_thread.joinable()) devices_thread.join();
  const auto path = text_of(path_edit);
  EnableWindow(GetDlgItem(root, refresh_id), FALSE);
  EnableWindow(connect_button, FALSE);
  EnableWindow(GetDlgItem(root, browse_id), FALSE); EnableWindow(path_edit, FALSE);
  SetWindowTextW(status_text, L"正在启动或检查 ADB 服务…可打开“调试日志”查看进度。");
  diagnostic->write("=== 开始刷新设备；ADB=" + scan::utf8(path) + "；总期限 20 秒 ===");
  devices_thread = std::thread([path] {
    auto* devices = new DeviceList;
    try {
      *devices = scan::search_devices([&](const auto& args, DWORD timeout) {
        return adb(path, args, timeout, &closing);
      }, [](const auto& step) {
        diagnostic->write(step); status(widen(step) + L"…详情见“调试日志”。");
      }, [] { return closing.load(); }, [](DWORD duration) {
        for (DWORD elapsed = 0; elapsed < duration && !closing; elapsed += 25) Sleep(25);
      });
    } catch (const std::exception& error) {
      devices->error = widen(error.what());
      diagnostic->write("[错误] 刷新失败: " + std::string(error.what()));
    } catch (...) {
      devices->error = L"未知设备搜索异常";
      diagnostic->write("[错误] 未知设备搜索异常");
    }
    diagnostic->write("刷新结束: " + scan::utf8(scan::device_summary(*devices)));
    if (!PostMessageW(root, message_devices, 0, reinterpret_cast<LPARAM>(devices))) delete devices;
  });
}
void write_line(SOCKET socket, const std::string& line) {
  const std::string data = line + "\n";
  std::size_t offset = 0;
  while (offset < data.size()) {
    const int count = send(socket, data.data() + offset, static_cast<int>(data.size() - offset), 0);
    if (count <= 0) throw std::runtime_error("发送数据失败 WSA=" + std::to_string(WSAGetLastError()));
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
      if (buffer_.size() > scan::max_bytes * 2 + 64) throw std::runtime_error("手机协议帧超长");
      char data[2048]; const int size = recv(socket_, data, sizeof(data), 0);
      if (!size) throw std::runtime_error("手机已关闭连接");
      if (size < 0) throw std::runtime_error("接收数据失败 WSA=" + std::to_string(WSAGetLastError()));
      buffer_.append(data, size);
    }
  }
};
SOCKET connect_local(int port) {
  SOCKET socket = ::socket(AF_INET, SOCK_STREAM, IPPROTO_TCP);
  if (socket == INVALID_SOCKET) throw std::runtime_error("创建 socket 失败 WSA=" + std::to_string(WSAGetLastError()));
  DWORD timeout = 3500;
  BOOL no_delay = TRUE;
  setsockopt(socket, IPPROTO_TCP, TCP_NODELAY, reinterpret_cast<char*>(&no_delay), sizeof(no_delay));
  setsockopt(socket, SOL_SOCKET, SO_RCVTIMEO, reinterpret_cast<char*>(&timeout), sizeof(timeout));
  setsockopt(socket, SOL_SOCKET, SO_SNDTIMEO, reinterpret_cast<char*>(&timeout), sizeof(timeout));
  sockaddr_in address{};
  address.sin_family = AF_INET; address.sin_port = htons(static_cast<u_short>(port));
  address.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
  if (connect(socket, reinterpret_cast<sockaddr*>(&address), sizeof(address)) != 0) {
    const auto error = WSAGetLastError();
    closesocket(socket); throw std::runtime_error("连接本机转发端口失败 WSA=" + std::to_string(error));
  }
  return socket;
}
void receive(SOCKET socket, scan::DeliveryCache& results) {
  Lines lines(socket);
  diagnostic->write("正在等待手机协议握手");
  if (lines.read() != "SCANRECORDER/1") throw std::runtime_error("手机协议版本不匹配");
  write_line(socket, "READY/1");
  diagnostic->write("手机握手成功，连接已建立；输入须由用户勾选启用");
  status(L"已连接手机。勾选“启用输入”，然后在 Excel 或其他软件中选好输入位置。");
  while (!stopping) {
    const auto line = lines.read();
    if (line == "PING") { write_line(socket, enabled ? "PONG ACTIVE" : "PONG PAUSED"); continue; }
    const auto frame = scan::decode(line);
    if (!frame) throw std::runtime_error("收到无效手机协议帧（不记录内容）");
    const auto [result, fresh] = results.submit(*frame, [](const auto& text) { return scan::type_text(text, enabled); });
    if (fresh && result == "OK") ++submitted;
    write_line(socket, "RESULT " + frame->id + " " + result);
    diagnostic->write("键盘提交状态=" + result + (fresh ? "" : "（重复请求，复用回执）"));
    status(result == "OK" ? L"已提交键盘输入和 Enter；请以目标软件实际显示为准。" :
      L"本条未完整提交。请查看手机记录状态，核对电脑后再手动补发。");
  }
}
int forward(const std::wstring& path, const std::wstring& serial) {
  diagnostic->write("正在为指定手机建立 USB 端口转发");
  const auto output = adb(path, {L"-s", serial, L"forward", L"tcp:0", L"tcp:28461"}, 5000, &stopping);
  // adb may print daemon startup messages before the allocated port.
  std::istringstream lines(output); std::string line; int port = 0;
  while (std::getline(lines, line)) {
    if (!line.empty() && line.back() == '\r') line.pop_back();
    if (!line.empty() && std::all_of(line.begin(), line.end(), [](char c) { return c >= '0' && c <= '9'; })) {
      const auto parsed = std::stoul(line); if (parsed > 0 && parsed <= 65535) port = static_cast<int>(parsed);
    }
  }
  if (!port) throw std::runtime_error("ADB 未返回有效的本机转发端口");
  diagnostic->write("USB 转发已建立，本机端口=" + std::to_string(port));
  return port;
}
void connection(std::wstring path, std::wstring serial) {
  int port = 0, failures = 0;
  scan::DeliveryCache results;
  while (!stopping) {
    std::string failure;
    try {
      if (!port) port = forward(path, serial);
      diagnostic->write("正在连接本机转发端口 " + std::to_string(port));
      const SOCKET socket = connect_local(port);
      active_socket = socket;
      try { receive(socket, results); }
      catch (const std::exception& error) { failure = error.what(); }
      catch (...) { failure = "未知接收异常"; }
      active_socket = INVALID_SOCKET; closesocket(socket);
      failures = 0;
    } catch (const std::exception& error) { failure = error.what(); }
    catch (...) { failure = "未知连接异常"; }
    if (!stopping) {
      diagnostic->write("[错误] 连接未就绪: " + failure);
      status(L"连接未就绪：" + widen(failure.substr(0, 160)) + L"\r\n详情见“调试日志”；请检查手机扫码枪模式。");
      for (int i = 0; i < 10 && !stopping; ++i) std::this_thread::sleep_for(std::chrono::milliseconds(100));
      if (++failures >= 5 && port) {
        try { adb(path, {L"-s", serial, L"forward", L"--remove", L"tcp:" + std::to_wstring(port)}, 2000, &stopping); }
        catch (const std::exception& error) { diagnostic->write("[错误] 重建前清理转发失败: " + std::string(error.what())); }
        port = 0; failures = 0;
      }
    }
  }
  if (port) {
    try { adb(path, {L"-s", serial, L"forward", L"--remove", L"tcp:" + std::to_wstring(port)}, 2000); }
    catch (const std::exception& error) { diagnostic->write("[错误] 清理本次转发失败: " + std::string(error.what())); }
  }
  diagnostic->write("接收连接已结束；输入已暂停");
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
void update_log() {
  if (!log_edit) return;
  const bool follow = SendMessageW(GetDlgItem(log_window, 204), BM_GETCHECK, 0, 0) == BST_CHECKED;
  const auto first_line = SendMessageW(log_edit, EM_GETFIRSTVISIBLELINE, 0, 0);
  DWORD selection_start = 0, selection_end = 0;
  SendMessageW(log_edit, EM_GETSEL, reinterpret_cast<WPARAM>(&selection_start), reinterpret_cast<LPARAM>(&selection_end));
  const auto text = diagnostic->snapshot();
  SetWindowTextW(log_edit, text.c_str());
  if (follow) {
    SendMessageW(log_edit, EM_SETSEL, text.size(), text.size());
    SendMessageW(log_edit, EM_SCROLLCARET, 0, 0);
  } else {
    SendMessageW(log_edit, EM_SETSEL, selection_start, selection_end);
    SendMessageW(log_edit, EM_LINESCROLL, 0, first_line);
  }
}
void update_resources() {
  resource_sample = resource_monitor.sample();
  if (resource_text) SetWindowTextW(resource_text, scan::resource_summary(resource_sample).c_str());
  if (log_resource_text) SetWindowTextW(log_resource_text, scan::resource_summary(resource_sample, true).c_str());
  if (++resource_ticks % 10 == 0 || !resource_sample.error.empty()) {
    diagnostic->write(resource_sample.error.empty() ? scan::utf8(scan::resource_summary(resource_sample, true)) : resource_sample.error);
  }
}
void copy_log() {
  const auto text = diagnostic->snapshot();
  const auto bytes = (text.size() + 1) * sizeof(wchar_t);
  HGLOBAL memory = GlobalAlloc(GMEM_MOVEABLE, bytes);
  if (!memory) throw std::runtime_error("无法分配复制日志所需的内存");
  void* data = GlobalLock(memory);
  if (!data) { GlobalFree(memory); throw std::runtime_error("无法读取日志内存"); }
  memcpy(data, text.c_str(), bytes); GlobalUnlock(memory);
  if (!OpenClipboard(log_window)) { GlobalFree(memory); throw std::runtime_error("剪贴板正被占用，请重试"); }
  EmptyClipboard();
  const bool copied = SetClipboardData(CF_UNICODETEXT, memory) != nullptr;
  CloseClipboard();
  if (!copied) { GlobalFree(memory); throw std::runtime_error("复制日志失败"); }
}
void save_log() {
  wchar_t file[32768] = L"receiver-debug.txt";
  OPENFILENAMEW dialog{}; dialog.lStructSize = sizeof(dialog); dialog.hwndOwner = log_window;
  dialog.lpstrFile = file; dialog.nMaxFile = 32768;
  dialog.lpstrFilter = L"UTF-8 text\0*.txt\0\0";
  dialog.lpstrDefExt = L"txt";
  const auto folder = config_path.substr(0, config_path.find_last_of(L"\\/"));
  dialog.lpstrInitialDir = folder.c_str();
  dialog.Flags = OFN_OVERWRITEPROMPT | OFN_PATHMUSTEXIST | OFN_NOCHANGEDIR;
  if (!GetSaveFileNameW(&dialog)) {
    const auto code = CommDlgExtendedError();
    if (code) throw std::runtime_error("保存对话框失败 Win32=" + std::to_string(code));
    return;
  }
  const auto text = std::string("\xef\xbb\xbf") + scan::utf8(diagnostic->snapshot());
  scan::Handle file_handle(CreateFileW(file, GENERIC_WRITE, 0, nullptr, CREATE_ALWAYS, FILE_ATTRIBUTE_NORMAL, nullptr));
  if (file_handle.value == INVALID_HANDLE_VALUE) throw std::runtime_error("创建日志文件失败 Win32=" + std::to_string(GetLastError()));
  DWORD count = 0;
  if (!WriteFile(file_handle.value, text.data(), static_cast<DWORD>(text.size()), &count, nullptr) || count != text.size()) {
    throw std::runtime_error("写入日志文件失败 Win32=" + std::to_string(GetLastError()));
  }
  diagnostic->write("用户已保存调试日志");
}
LRESULT CALLBACK log_proc(HWND window, UINT message, WPARAM w, LPARAM l) {
  if (message == WM_CREATE) {
    log_window = window;
    log_resource_text = CreateWindowExW(0, L"STATIC", scan::resource_summary(resource_sample, true).c_str(),
      WS_CHILD | WS_VISIBLE, 12, 12, 750, 28, window, nullptr, GetModuleHandleW(nullptr), nullptr);
    SendMessageW(log_resource_text, WM_SETFONT, reinterpret_cast<WPARAM>(font), TRUE);
    log_edit = CreateWindowExW(WS_EX_CLIENTEDGE, L"EDIT", L"",
      WS_CHILD | WS_VISIBLE | WS_VSCROLL | ES_MULTILINE | ES_READONLY | ES_AUTOVSCROLL,
      12, 46, 730, 356, window, reinterpret_cast<HMENU>(200), GetModuleHandleW(nullptr), nullptr);
    SendMessageW(log_edit, WM_SETFONT, reinterpret_cast<WPARAM>(font), TRUE);
    SendMessageW(log_edit, EM_SETLIMITTEXT, 130000, 0);
    const wchar_t* labels[] = {L"复制日志", L"保存日志…", L"清空显示", L"自动滚动"};
    for (int i = 0; i < 4; ++i) {
      HWND button = CreateWindowExW(0, L"BUTTON", labels[i], WS_CHILD | WS_VISIBLE | WS_TABSTOP |
        (i == 3 ? BS_AUTOCHECKBOX : BS_PUSHBUTTON), 12 + 140 * i, 420, 128, 32,
        window, reinterpret_cast<HMENU>(static_cast<INT_PTR>(201 + i)), GetModuleHandleW(nullptr), nullptr);
      SendMessageW(button, WM_SETFONT, reinterpret_cast<WPARAM>(font), TRUE);
      if (i == 3) SendMessageW(button, BM_SETCHECK, BST_CHECKED, 0);
    }
    update_log(); return 0;
  }
  if (message == WM_SIZE) {
    const int width = LOWORD(l), height = HIWORD(l);
    if (log_resource_text) MoveWindow(log_resource_text, 12, 12, std::max(100, width - 24), 28, TRUE);
    if (log_edit) MoveWindow(log_edit, 12, 46, std::max(100, width - 24), std::max(40, height - 104), TRUE);
    for (int i = 0; i < 4; ++i) MoveWindow(GetDlgItem(window, 201 + i), 12 + 140 * i,
      std::max(60, height - 46), 128, 32, TRUE);
    return 0;
  }
  if (message == WM_GETMINMAXINFO) {
    auto* limits = reinterpret_cast<MINMAXINFO*>(l);
    limits->ptMinTrackSize = {800, 280}; return 0;
  }
  if (message == WM_COMMAND) {
    try {
      if (LOWORD(w) == 201) copy_log();
      else if (LOWORD(w) == 202) save_log();
      else if (LOWORD(w) == 203) diagnostic->clear();
    } catch (const std::exception& error) {
      diagnostic->write("[错误] " + std::string(error.what()));
      MessageBoxW(window, widen(error.what()).c_str(), L"日志操作失败", MB_OK | MB_ICONERROR);
    }
    return 0;
  }
  if (message == WM_CLOSE) { ShowWindow(window, SW_HIDE); return 0; }
  if (message == WM_DESTROY) { log_resource_text = log_edit = log_window = nullptr; return 0; }
  return DefWindowProcW(window, message, w, l);
}
void show_log() {
  if (!log_window) {
    CreateWindowExW(0, L"ScanRecorderDiagnostics", L"扫码记录 · 调试日志（不含扫码原文）",
      WS_OVERLAPPEDWINDOW, CW_USEDEFAULT, CW_USEDEFAULT, 800, 540,
      root, nullptr, GetModuleHandleW(nullptr), nullptr);
  }
  if (!log_window) throw std::runtime_error("无法打开调试窗口 Win32=" + std::to_string(GetLastError()));
  update_log(); ShowWindow(log_window, SW_SHOWNORMAL); SetForegroundWindow(log_window);
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
  count_text = control(L"STATIC", L"本次已提交：0 条", 0, 0, 20, 264, 470, 26);
  control(L"BUTTON", L"调试日志", BS_PUSHBUTTON | WS_TABSTOP, log_id, 510, 260, 130, 30);
  resource_text = control(L"STATIC", L"本程序 CPU 采样中…", 0, 0, 20, 300, 620, 26);
  control(L"STATIC", L"Excel：先将录入列设为“文本”，保留前导零和长编号。\r\n"
    L"手机确认后才输入；断线不自动补发，长按手机记录可补发。\r\n"
    L"退出本程序即停止接收。扫码输入不修改剪贴板。", 0, 0, 20, 338, 620, 78);
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
  else if (id == log_id) show_log();
  else if (id == enabled_id) {
    enabled = SendMessageW(enabled_box, BM_GETCHECK, 0, 0) == BST_CHECKED;
    diagnostic->write(enabled ? "用户启用键盘输入" : "用户暂停键盘输入");
  }
  else if (id == connect_id) {
    if (searching) return;
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
    if (!WritePrivateProfileStringW(L"receiver", L"adb", path.c_str(), config_path.c_str())) {
      diagnostic->write("[错误] ADB 路径配置保存失败 Win32=" + std::to_string(GetLastError()));
    }
    diagnostic->write("开始连接指定手机 " + scan::utf8(serial));
    SetWindowTextW(connect_button, L"断开");
    EnableWindow(GetDlgItem(root, refresh_id), FALSE); EnableWindow(GetDlgItem(root, browse_id), FALSE);
    EnableWindow(device_box, FALSE); EnableWindow(path_edit, FALSE);
    try { connection_thread = std::thread(connection, path, std::wstring(serial)); }
    catch (...) {
      stopping = true;
      SetWindowTextW(connect_button, L"连接");
      EnableWindow(GetDlgItem(root, refresh_id), TRUE); EnableWindow(GetDlgItem(root, browse_id), TRUE);
      EnableWindow(device_box, TRUE); EnableWindow(path_edit, TRUE);
      throw;
    }
  }
}
LRESULT CALLBACK window_proc(HWND window, UINT message, WPARAM w, LPARAM l) {
  switch (message) {
    case WM_CREATE:
      root = window; diagnostic->attach(window, message_log); create_controls();
      update_resources(); SetTimer(window, 1, 1000, nullptr);
      diagnostic->write("接收程序 0.5.1 已启动；日志仅记录连接诊断，不含扫码原文"); return 0;
    case WM_COMMAND:
      try { command(LOWORD(w)); }
      catch (const std::exception& error) {
        diagnostic->write("[错误] 操作失败: " + std::string(error.what()));
        SetWindowTextW(status_text, (L"操作失败：" + widen(error.what()) + L"；详情见调试日志。").c_str());
        if (LOWORD(w) == refresh_id) {
          searching = false;
          EnableWindow(GetDlgItem(root, refresh_id), TRUE); EnableWindow(connect_button, TRUE);
          EnableWindow(GetDlgItem(root, browse_id), TRUE); EnableWindow(path_edit, TRUE);
        }
      }
      return 0;
    case message_log: update_log(); return 0;
    case WM_TIMER: if (w == 1) update_resources(); return 0;
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
      const auto text = scan::device_summary(*devices);
      SetWindowTextW(status_text, text.c_str());
      delete devices; searching = false;
      EnableWindow(GetDlgItem(root, refresh_id), stopping); EnableWindow(connect_button, TRUE);
      EnableWindow(GetDlgItem(root, browse_id), TRUE); EnableWindow(path_edit, TRUE); return 0;
    }
    case message_done:
      if (connection_thread.joinable()) connection_thread.join();
      EnableWindow(connect_button, TRUE);
      SetWindowTextW(connect_button, L"连接"); SetWindowTextW(status_text, L"已断开，输入已暂停。");
      EnableWindow(GetDlgItem(root, refresh_id), TRUE); EnableWindow(GetDlgItem(root, browse_id), TRUE);
      EnableWindow(device_box, TRUE); EnableWindow(path_edit, TRUE); return 0;
    case WM_DESTROY:
      closing = true; diagnostic->attach(nullptr, 0);
      KillTimer(window, 1);
      stop();
      if (connection_thread.joinable()) connection_thread.join();
      if (devices_thread.joinable()) devices_thread.join();
      if (log_window) DestroyWindow(log_window);
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
  WNDCLASSW log_class = cls;
  log_class.lpfnWndProc = log_proc; log_class.lpszClassName = L"ScanRecorderDiagnostics";
  RegisterClassW(&log_class);
  HWND window = CreateWindowExW(0, cls.lpszClassName, L"扫码记录 · USB 接收程序 0.5.1",
    WS_OVERLAPPED | WS_CAPTION | WS_SYSMENU | WS_MINIMIZEBOX, CW_USEDEFAULT, CW_USEDEFAULT,
    680, 480, nullptr, nullptr, instance, nullptr);
  if (!window) return 3;
  ShowWindow(window, show); UpdateWindow(window);
  MSG message{};
  while (GetMessageW(&message, nullptr, 0, 0) > 0) {
    if (!(log_window && IsDialogMessageW(log_window, &message)) && !IsDialogMessageW(window, &message)) {
      TranslateMessage(&message); DispatchMessageW(&message);
    }
  }
  WSACleanup(); CloseHandle(mutex); return static_cast<int>(message.wParam);
}
