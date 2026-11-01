#pragma once
#ifndef WIN32_LEAN_AND_MEAN
#define WIN32_LEAN_AND_MEAN
#endif
#include <windows.h>
#include <algorithm>
#include <atomic>
#include <chrono>
#include <condition_variable>
#include <functional>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <string>
#include <thread>
#include <vector>

namespace scan {
using Trace = std::function<void(const std::string&)>;
inline std::string utf8(const std::wstring& value) {
  const int size = WideCharToMultiByte(CP_UTF8, 0, value.data(), static_cast<int>(value.size()),
    nullptr, 0, nullptr, nullptr);
  std::string result(size, '\0');
  if (size) WideCharToMultiByte(CP_UTF8, 0, value.data(), static_cast<int>(value.size()),
    result.data(), size, nullptr, nullptr);
  return result;
}
inline std::wstring quote(const std::wstring& argument) {
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
struct Handle {
  HANDLE value = nullptr;
  Handle() = default;
  explicit Handle(HANDLE handle) : value(handle) {}
  Handle(const Handle&) = delete;
  Handle& operator=(const Handle&) = delete;
  ~Handle() { reset(); }
  void reset(HANDLE handle = nullptr) {
    if (value && value != INVALID_HANDLE_VALUE) CloseHandle(value);
    value = handle;
  }
};

// Supervise the entire operation, including CreateProcess and synchronous pipe APIs.
// A stalled launch/read must not prevent the caller from timing out or cancelling.
class AdbRunner {
  struct Operation {
    std::mutex mutex;
    std::condition_variable changed;
    std::atomic<bool> cancelled{false};
    bool done = false;
    HANDLE process = nullptr, thread = nullptr;
    DWORD pid = 0;
    std::string stage = "准备启动", output, error;
  };
  std::shared_ptr<std::atomic<bool>> busy_ = std::make_shared<std::atomic<bool>>(false);
  Trace trace_;
  static constexpr std::size_t output_limit = 65536;

  static void stage(const std::shared_ptr<Operation>& operation, const std::string& value) {
    std::lock_guard lock(operation->mutex);
    operation->stage = value;
  }
  static void cancel(const std::shared_ptr<Operation>& operation) {
    operation->cancelled = true;
    // Handles stay valid under this lock; the worker never holds it during I/O.
    std::lock_guard lock(operation->mutex);
    if (operation->thread) CancelSynchronousIo(operation->thread);
    if (operation->process) TerminateProcess(operation->process, ERROR_CANCELLED);
  }
  static std::runtime_error win_error(const char* api) {
    return std::runtime_error(std::string(api) + " Win32=" + std::to_string(GetLastError()));
  }
  static std::string execute(const std::shared_ptr<Operation>& operation, const std::wstring& path,
      const std::vector<std::wstring>& args, const Trace& trace) {
    SECURITY_ATTRIBUTES attributes{sizeof(attributes), nullptr, TRUE};
    Handle input, output, null_input;
    stage(operation, "创建输出管道 (CreatePipe)");
    if (!CreatePipe(&input.value, &output.value, &attributes, 0)) throw win_error("CreatePipe");
    if (!SetHandleInformation(input.value, HANDLE_FLAG_INHERIT, 0)) throw win_error("SetHandleInformation");
    null_input.reset(CreateFileW(L"NUL", GENERIC_READ, FILE_SHARE_READ | FILE_SHARE_WRITE,
      &attributes, OPEN_EXISTING, FILE_ATTRIBUTE_NORMAL, nullptr));
    if (null_input.value == INVALID_HANDLE_VALUE) throw win_error("CreateFile NUL");

    STARTUPINFOEXW startup{};
    startup.StartupInfo.cb = sizeof(startup);
    startup.StartupInfo.dwFlags = STARTF_USESTDHANDLES;
    startup.StartupInfo.hStdOutput = startup.StartupInfo.hStdError = output.value;
    startup.StartupInfo.hStdInput = null_input.value;
    SIZE_T attribute_size = 0;
    InitializeProcThreadAttributeList(nullptr, 1, 0, &attribute_size);
    std::vector<unsigned char> attribute_data(attribute_size);
    startup.lpAttributeList = reinterpret_cast<LPPROC_THREAD_ATTRIBUTE_LIST>(attribute_data.data());
    if (!InitializeProcThreadAttributeList(startup.lpAttributeList, 1, 0, &attribute_size)) {
      throw win_error("InitializeProcThreadAttributeList");
    }
    struct Attributes {
      LPPROC_THREAD_ATTRIBUTE_LIST value;
      ~Attributes() { DeleteProcThreadAttributeList(value); }
    } attributes_cleanup{startup.lpAttributeList};
    HANDLE inherited[] = {output.value, null_input.value};
    if (!UpdateProcThreadAttribute(startup.lpAttributeList, 0, PROC_THREAD_ATTRIBUTE_HANDLE_LIST,
        inherited, sizeof(inherited), nullptr, nullptr)) throw win_error("UpdateProcThreadAttribute");
    std::wstring command = quote(path);
    for (const auto& argument : args) command += L" " + quote(argument);
    stage(operation, "创建 ADB 进程 (CreateProcessW)");
    trace("CreateProcessW 开始: " + utf8(command));
    if (operation->cancelled) throw std::runtime_error("操作已取消");
    PROCESS_INFORMATION process{};
    const BOOL created = CreateProcessW(path.c_str(), command.data(), nullptr, nullptr, TRUE,
      CREATE_NO_WINDOW | CREATE_SUSPENDED | EXTENDED_STARTUPINFO_PRESENT,
      nullptr, nullptr, &startup.StartupInfo, &process);
    if (!created) throw win_error("CreateProcessW");
    Handle process_handle(process.hProcess), process_thread(process.hThread), own_thread;
    struct ClientCleanup {
      HANDLE process;
      ~ClientCleanup() {
        if (WaitForSingleObject(process, 0) == WAIT_TIMEOUT) TerminateProcess(process, ERROR_CANCELLED);
      }
    } client_cleanup{process.hProcess};
    if (!DuplicateHandle(GetCurrentProcess(), GetCurrentThread(), GetCurrentProcess(), &own_thread.value,
        0, FALSE, DUPLICATE_SAME_ACCESS)) throw win_error("DuplicateHandle");
    // The handles below are borrowed only until execute() returns.
    struct Published {
      std::shared_ptr<Operation> operation;
      ~Published() {
        std::lock_guard lock(operation->mutex);
        operation->process = operation->thread = nullptr;
      }
    } published{operation};
    {
      std::lock_guard lock(operation->mutex);
      operation->process = process.hProcess;
      operation->thread = own_thread.value;
      operation->pid = process.dwProcessId;
    }
    if (operation->cancelled) {
      TerminateProcess(process.hProcess, ERROR_CANCELLED);
      throw std::runtime_error("启动返回时操作已取消；已停止本次 ADB 客户端");
    }
    if (ResumeThread(process.hThread) == static_cast<DWORD>(-1)) {
      const auto error = win_error("ResumeThread");
      TerminateProcess(process.hProcess, ERROR_CANCELLED);
      throw error;
    }
    output.reset(); null_input.reset();
    trace("ADB 客户端已启动 PID=" + std::to_string(process.dwProcessId));
    trace("等待读取 ADB 输出和退出结果");
    bool exited = false;
    for (;;) {
      if (operation->cancelled) {
        TerminateProcess(process.hProcess, ERROR_CANCELLED);
        throw std::runtime_error("操作已取消");
      }
      stage(operation, "读取 ADB 输出 (PeekNamedPipe)");
      DWORD available = 0;
      if (!PeekNamedPipe(input.value, nullptr, 0, nullptr, &available, nullptr)) {
        if (GetLastError() != ERROR_BROKEN_PIPE) throw win_error("PeekNamedPipe");
        break;
      }
      if (available) {
        char buffer[4096]; DWORD count = 0;
        stage(operation, "读取 ADB 输出 (ReadFile)");
        if (!ReadFile(input.value, buffer, std::min<DWORD>(available, sizeof(buffer)), &count, nullptr)) {
          if (GetLastError() == ERROR_BROKEN_PIPE) break;
          throw win_error("ReadFile");
        }
        std::lock_guard lock(operation->mutex);
        const auto remaining = output_limit - operation->output.size();
        operation->output.append(buffer, std::min<std::size_t>(count, remaining));
        if (count > remaining) {
          TerminateProcess(process.hProcess, ERROR_BUFFER_OVERFLOW);
          throw std::runtime_error("ADB 输出超过 64 KiB，已停止本次客户端");
        }
      } else if (exited) break; // Do not wait for a daemon to close inherited handles.
      else {
        stage(operation, "等待 ADB 返回 (WaitForSingleObject)");
        const DWORD result = WaitForSingleObject(process.hProcess, 20);
        if (result == WAIT_FAILED) throw win_error("WaitForSingleObject");
        exited = result == WAIT_OBJECT_0;
      }
    }
    stage(operation, "等待 ADB 退出 (WaitForSingleObject)");
    while (WaitForSingleObject(process.hProcess, 20) == WAIT_TIMEOUT) {
      if (operation->cancelled) {
        TerminateProcess(process.hProcess, ERROR_CANCELLED);
        throw std::runtime_error("操作已取消");
      }
    }
    DWORD code = 0;
    if (!GetExitCodeProcess(process.hProcess, &code)) throw win_error("GetExitCodeProcess");
    trace("ADB 客户端退出 PID=" + std::to_string(process.dwProcessId) + " exit=" + std::to_string(code));
    if (code) throw std::runtime_error("ADB exit=" + std::to_string(code));
    std::lock_guard lock(operation->mutex);
    return operation->output;
  }

 public:
  explicit AdbRunner(Trace trace = [](const auto&) {}) : trace_(std::move(trace)) {}
  std::string run(const std::wstring& path, const std::vector<std::wstring>& args,
      DWORD timeout_ms = 5000, const std::atomic<bool>* cancelled = nullptr) {
    const auto began = std::chrono::steady_clock::now();
    const auto deadline = began + std::chrono::milliseconds(timeout_ms);
    if (busy_->exchange(true)) throw std::runtime_error("上一次 ADB 调用仍在清理，请稍后重试或重新打开接收程序");
    std::shared_ptr<Operation> operation;
    try { operation = std::make_shared<Operation>(); }
    catch (...) { *busy_ = false; throw; }
    auto busy = busy_;
    std::thread worker;
    try {
      worker = std::thread([operation, busy, path, args, trace = trace_] {
        std::string error;
        try { execute(operation, path, args, trace); }
        catch (const std::exception& failure) { error = failure.what(); }
        catch (...) { error = "未知 ADB 异常"; }
        {
          std::lock_guard lock(operation->mutex);
          operation->error = error;
          operation->done = true;
          *busy = false;
        }
        operation->changed.notify_all();
      });
    } catch (...) { *busy_ = false; throw; }
    std::unique_lock lock(operation->mutex);
    auto next_progress = began + std::chrono::seconds(1);
    while (!operation->done && !(cancelled && cancelled->load()) &&
        std::chrono::steady_clock::now() < deadline) {
      operation->changed.wait_until(lock, std::min(deadline,
        std::chrono::steady_clock::now() + std::chrono::milliseconds(50)));
      if (!operation->done && std::chrono::steady_clock::now() >= next_progress) {
        const auto progress = "仍在等待: " + operation->stage + " PID=" + std::to_string(operation->pid);
        lock.unlock(); trace_(progress); lock.lock();
        next_progress += std::chrono::seconds(1);
      }
    }
    const bool done = operation->done;
    const auto output = operation->output;
    auto error = operation->error;
    const auto last_stage = operation->stage;
    const auto pid = operation->pid;
    lock.unlock();
    if (done) worker.join();
    else {
      cancel(operation);
      worker.detach(); // A stalled Windows API must not hold the UI/refresh thread hostage.
      error = cancelled && cancelled->load() ? "操作已取消" : "ADB 操作超时";
    }
    const auto elapsed = std::chrono::duration_cast<std::chrono::milliseconds>(
      std::chrono::steady_clock::now() - began).count();
    trace_("ADB 调用结束 耗时=" + std::to_string(elapsed) + "ms 阶段=" + last_stage +
      " PID=" + std::to_string(pid) + (error.empty() ? " 成功" : " 错误=" + error));
    if (!output.empty()) trace_("ADB 输出:\r\n" + output);
    if (!error.empty()) throw std::runtime_error(error + "；阶段=" + last_stage +
      "；耗时=" + std::to_string(elapsed) + "ms；PID=" + std::to_string(pid) +
      (output.empty() ? "" : "\r\n" + output.substr(0, 4096)));
    return output;
  }
};
}
