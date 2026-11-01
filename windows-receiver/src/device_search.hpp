#pragma once
#include "adb_process.hpp"
#include <sstream>

namespace scan {
struct DeviceList {
  std::vector<std::wstring> serials;
  std::size_t unauthorized = 0, offline = 0;
  std::wstring error;
};
inline bool serial_valid(const std::string& serial) {
  return !serial.empty() && serial.size() <= 128 && std::all_of(serial.begin(), serial.end(), [](char c) {
    return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') ||
      c == '-' || c == '_' || c == '.';
  });
}
inline DeviceList parse_devices(const std::string& output) {
  DeviceList result;
  std::istringstream lines(output);
  std::string line;
  while (std::getline(lines, line)) {
    std::istringstream fields(line);
    std::string serial, state; fields >> serial >> state;
    if (!serial_valid(serial)) continue;
    if (state == "device") {
      // ADB USB serials are ASCII; do not treat startup messages as device rows.
      result.serials.emplace_back(serial.begin(), serial.end());
    } else if (state == "unauthorized") ++result.unauthorized;
    else if (state == "offline") ++result.offline;
  }
  return result;
}
inline std::wstring device_summary(const DeviceList& devices) {
  if (!devices.error.empty()) {
    if (devices.error.find(L"超时") != std::wstring::npos) return L"设备搜索超时，已结束等待；请打开“调试日志”查看阻塞阶段后重试。";
    return L"设备搜索失败：" + devices.error.substr(0, 52) + L"；详情见“调试日志”。";
  }
  if (!devices.serials.empty()) return L"已找到 " + std::to_wstring(devices.serials.size()) +
    L" 台可用设备，请选择手机并连接。" +
    (devices.unauthorized || devices.offline ? L"另有未授权或离线设备，详见调试日志。" : L"");
  if (devices.unauthorized) return L"手机尚未授权：请解锁手机并允许 USB 调试，然后刷新。";
  if (devices.offline) return L"检测到离线手机：请检查数据线和 USB 调试状态，然后刷新。";
  return L"ADB 已就绪，未发现 USB 设备；请检查数据线、USB 调试和驱动后刷新。";
}
// Calls and waits share a single deadline, not a fresh timeout for each retry.
template<class Call, class Report, class Cancelled, class Wait>
DeviceList search_devices(Call call, Report report, Cancelled cancelled, Wait wait,
    ULONGLONG budget_ms = 20000) {
  const auto deadline = GetTickCount64() + budget_ms;
  auto remaining = [&](DWORD limit) {
    const auto now = GetTickCount64();
    if (cancelled()) throw std::runtime_error("设备搜索已取消");
    if (now >= deadline) throw std::runtime_error("设备搜索超时（总等待期限已到）");
    return static_cast<DWORD>(std::min<ULONGLONG>(limit, deadline - now));
  };
  report("启动或检查 ADB 服务（最多 12 秒）");
  call(std::vector<std::wstring>{L"start-server"}, remaining(12000));
  DeviceList devices;
  for (int attempt = 1; attempt <= 4; ++attempt) {
    report("获取设备列表，第 " + std::to_string(attempt) + "/4 次");
    devices = parse_devices(call(std::vector<std::wstring>{L"devices", L"-l"}, remaining(4000)));
    if (!devices.serials.empty() || devices.unauthorized || attempt == 4) return devices;
    report(devices.offline ? "发现离线设备，短暂等待后重试" : "暂未发现设备，短暂等待 USB 就绪后重试");
    wait(std::min<DWORD>(remaining(400), 400));
  }
  return devices;
}
}
