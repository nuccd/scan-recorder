#include "../src/adb_process.hpp"
#include "../src/device_search.hpp"
#include "../src/diagnostics.hpp"
#include "../src/resources.hpp"
#include <cstdio>
#include <iostream>

namespace {
int checks = 0;
void check(bool pass, const char* description) {
  ++checks;
  if (!pass) throw std::runtime_error("check " + std::to_string(checks) + ": " + description);
}
std::string failure(const std::function<void()>& action) {
  try { action(); } catch (const std::exception& error) { return error.what(); }
  throw std::runtime_error("expected exception");
}
int fixture(const std::wstring& mode, const std::wstring& self) {
  if (mode == L"start-server" || mode == L"devices") {
    wchar_t scenario[80]{};
    GetEnvironmentVariableW(L"SCAN_RECORDER_ADB_FIXTURE", scenario, 80);
    if (mode == L"start-server" && std::wstring(scenario) == L"hang") {
      std::fprintf(stderr, "fixture: waiting for daemon startup\n"); std::fflush(stderr); Sleep(30000);
    }
    if (mode == L"start-server" && std::wstring(scenario) == L"failure") {
      std::fprintf(stderr, "fixture: daemon startup failed, detailed reason\n"); return 7;
    }
    if (mode == L"devices") {
      std::printf("List of devices attached\nUSB_FIXTURE %s\n",
        std::wstring(scenario) == L"unauthorized" ? "unauthorized" : "device");
    }
    return 0;
  }
  if (mode == L"--fake-ok") { std::printf("OK 中文\n"); return 0; }
  if (mode == L"--fake-fail") { std::fprintf(stderr, "server could not start: diagnostic detail\n"); return 7; }
  if (mode == L"--fake-tail") {
    std::printf("%sTAIL-MARKER\n", std::string(10000, 'x').c_str()); return 0;
  }
  if (mode == L"--fake-overflow") {
    std::printf("%s", std::string(70000, 'x').c_str()); std::fflush(stdout); return 0;
  }
  if (mode == L"--fake-hold") { Sleep(900); return 0; }
  if (mode == L"--fake-daemon") {
    STARTUPINFOW startup{}; startup.cb = sizeof(startup);
    startup.dwFlags = STARTF_USESTDHANDLES;
    startup.hStdInput = GetStdHandle(STD_INPUT_HANDLE);
    startup.hStdOutput = GetStdHandle(STD_OUTPUT_HANDLE);
    startup.hStdError = GetStdHandle(STD_ERROR_HANDLE);
    PROCESS_INFORMATION process{};
    auto command = scan::quote(self) + L" --fake-hold";
    if (!CreateProcessW(self.c_str(), command.data(), nullptr, nullptr, TRUE, CREATE_NO_WINDOW,
        nullptr, nullptr, &startup, &process)) return 8;
    CloseHandle(process.hThread); CloseHandle(process.hProcess);
    std::printf("parent finished\n"); return 0;
  }
  if (mode == L"--fake-no-output") {
    CloseHandle(GetStdHandle(STD_OUTPUT_HANDLE)); CloseHandle(GetStdHandle(STD_ERROR_HANDLE));
    Sleep(5000); return 0;
  }
  if (mode == L"--fake-hang") { std::printf("waiting for server\n"); std::fflush(stdout); Sleep(5000); return 0; }
  return 9;
}
}
int wmain(int argc, wchar_t** argv) {
  wchar_t path[32768]{}; GetModuleFileNameW(nullptr, path, 32768);
  if (argc >= 2) return fixture(argv[1], path);
  try {
    auto log = std::make_shared<scan::DiagnosticLog>();
    scan::AdbRunner runner([log](const auto& text) { log->write(text); });
    check(runner.run(path, {L"--fake-ok"}).find("OK 中文") != std::string::npos, "capture UTF-8 output");
    const auto nonzero = failure([&] { runner.run(path, {L"--fake-fail"}); });
    check(nonzero.find("exit=7") != std::string::npos, "preserve exit code");
    check(nonzero.find("diagnostic detail") != std::string::npos, "preserve stderr in exception");
    check(nonzero.find("PID=") != std::string::npos, "include process ID");
    check(runner.run(path, {L"--fake-tail"}).find("TAIL-MARKER") != std::string::npos, "drain output after quick exit");
    auto began = GetTickCount64();
    check(runner.run(path, {L"--fake-daemon"}, 1500).find("parent finished") != std::string::npos,
      "daemon-held output pipe is not an EOF requirement");
    check(GetTickCount64() - began < 800, "return before descendant exits");
    began = GetTickCount64();
    const auto timed_out = failure([&] { runner.run(path, {L"--fake-hang"}, 250); });
    check(GetTickCount64() - began < 1200, "stalled client has bounded wait");
    check(timed_out.find("超时") != std::string::npos, "explicit timeout reason");
    check(timed_out.find("阶段=") != std::string::npos, "include stalled stage");
    check(timed_out.find("waiting for server") != std::string::npos, "retain partial output on timeout");
    Sleep(100); // Let the terminated fixture finish cleanup before the next command.
    check(runner.run(path, {L"--fake-ok"}).find("OK") != std::string::npos, "retry after timeout");
    began = GetTickCount64();
    const auto no_output = failure([&] { runner.run(path, {L"--fake-no-output"}, 250); });
    check(GetTickCount64() - began < 1200 && no_output.find("超时") != std::string::npos,
      "closed stdout does not disguise a hanging process");
    Sleep(100);
    std::atomic<bool> cancelled{false};
    std::thread cancel([&] { Sleep(120); cancelled = true; });
    const auto cancelled_error = failure([&] { runner.run(path, {L"--fake-hang"}, 5000, &cancelled); });
    cancel.join();
    check(cancelled_error.find("取消") != std::string::npos, "user cancellation returns an explicit reason");
    Sleep(100);
    check(failure([&] { runner.run(L"Z:\\scan-recorder-missing-adb.exe", {}); }).find("CreateProcessW Win32=") != std::string::npos,
      "missing executable reports Windows error");
    check(failure([&] { runner.run(path, {L"--fake-overflow"}); }).find("64 KiB") != std::string::npos,
      "bounded output is an error, never truncated success");
    auto delayed = std::make_shared<std::atomic<bool>>(true);
    auto launched = std::make_shared<std::atomic<bool>>(false);
    scan::AdbRunner stalled_worker([delayed, launched](const std::string& entry) {
      if (entry.rfind("CreateProcessW 开始", 0) == 0 && delayed->exchange(false)) Sleep(700);
      if (entry.rfind("ADB 客户端已启动", 0) == 0) *launched = true;
    });
    began = GetTickCount64();
    const auto stalled_error = failure([&] { stalled_worker.run(path, {L"--fake-ok"}, 150); });
    check(GetTickCount64() - began < 600 && stalled_error.find("CreateProcessW") != std::string::npos,
      "supervisor returns even while the worker itself is stalled before launch");
    check(failure([&] { stalled_worker.run(path, {L"--fake-ok"}); }).find("仍在清理") != std::string::npos,
      "one stalled worker cannot accumulate more launch threads");
    Sleep(750);
    check(!launched->load(), "late worker notices cancellation before starting the client");
    check(stalled_worker.run(path, {L"--fake-ok"}).find("OK") != std::string::npos,
      "stalled-worker cleanup eventually permits retry");

    const auto devices = scan::parse_devices("* daemon started successfully\r\nList of devices attached\r\n"
      "USB_123 device product:test\r\nUSB_456 unauthorized\r\nUSB_789 offline\r\n192.168.1.2:5555 device\r\n");
    check(devices.serials == std::vector<std::wstring>{L"USB_123"}, "only USB-style serials are selectable");
    check(devices.unauthorized == 1 && devices.offline == 1, "preserve unauthorized and offline state");
    int attempts = 0, starts = 0, waits = 0;
    const auto discovered = scan::search_devices([&](const auto& args, DWORD) {
      if (args[0] == L"start-server") { ++starts; return std::string(); }
      return ++attempts < 3 ? std::string("List of devices attached\n") : std::string("USB_123 device\n");
    }, [](const auto&) {}, [] { return false; }, [&](DWORD) { ++waits; });
    check(starts == 1 && attempts == 3 && waits == 2 && discovered.serials.size() == 1,
      "start server then retry initially empty enumeration");
    attempts = 0;
    const auto unauthorized = scan::search_devices([&](const auto& args, DWORD) {
      if (args[0] == L"start-server") return std::string();
      ++attempts; return std::string("USB_123 unauthorized\n");
    }, [](const auto&) {}, [] { return false; }, [](DWORD) {});
    check(attempts == 1 && scan::device_summary(unauthorized).find(L"授权") != std::wstring::npos,
      "unauthorized phone has an actionable message");
    check(failure([&] { scan::search_devices([](const auto&, DWORD) { return std::string(); },
      [](const auto&) {}, [] { return true; }, [](DWORD) {}); }).find("取消") != std::string::npos,
      "cancel device search before launching ADB");
    check(failure([&] { scan::search_devices([](const auto&, DWORD) { Sleep(15); return std::string(); },
      [](const auto&) {}, [] { return false; }, [](DWORD) {}, 10); }).find("超时") != std::string::npos,
      "search retries obey the shared deadline");
    for (int i = 0; i < 600; ++i) log->write(std::string(500, 'x'));
    check(log->snapshot().size() <= 120000, "diagnostics retain bounded history");
    log->clear(); check(log->snapshot().empty(), "clear diagnostic display");
    scan::ResourceMonitor monitor;
    const auto initial = monitor.sample();
    check(initial.error.empty() && initial.working_set > 0 && initial.private_bytes > 0,
      "read actual process working set and private commit");
    Sleep(30);
    const auto sampled = monitor.sample();
    check(sampled.cpu_ready && sampled.cpu >= 0 && sampled.cpu <= 100, "sample normalized CPU percentage");
    std::cout << checks << " ADB process, discovery and diagnostic checks passed\n";
  } catch (const std::exception& error) { std::cerr << error.what() << '\n'; return 1; }
  return 0;
}
