#pragma once
#include "adb_process.hpp"
#include <psapi.h>

namespace scan {
struct ResourceSample {
  double cpu = 0;
  bool cpu_ready = false;
  SIZE_T working_set = 0, private_bytes = 0;
  std::string error;
};
class ResourceMonitor {
  ULONGLONG previous_cpu_ = 0, previous_tick_ = 0;
  bool initialized_ = false;
  static ULONGLONG ticks(FILETIME value) {
    return (static_cast<ULONGLONG>(value.dwHighDateTime) << 32) | value.dwLowDateTime;
  }
 public:
  ResourceSample sample() {
    ResourceSample result;
    FILETIME created{}, exited{}, kernel{}, user{};
    if (GetProcessTimes(GetCurrentProcess(), &created, &exited, &kernel, &user)) {
      const auto cpu = ticks(kernel) + ticks(user);
      const auto now = GetTickCount64();
      const auto processors = std::max<DWORD>(1, GetActiveProcessorCount(ALL_PROCESSOR_GROUPS));
      if (initialized_ && now > previous_tick_ && cpu >= previous_cpu_) {
        result.cpu = std::clamp(100.0 * static_cast<double>(cpu - previous_cpu_) /
          (static_cast<double>(now - previous_tick_) * 10000.0 * processors), 0.0, 100.0);
        result.cpu_ready = true;
      }
      previous_cpu_ = cpu; previous_tick_ = now; initialized_ = true;
    } else result.error = "读取 CPU 失败 Win32=" + std::to_string(GetLastError());
    PROCESS_MEMORY_COUNTERS_EX memory{}; memory.cb = sizeof(memory);
    if (K32GetProcessMemoryInfo(GetCurrentProcess(), reinterpret_cast<PROCESS_MEMORY_COUNTERS*>(&memory), sizeof(memory))) {
      result.working_set = memory.WorkingSetSize;
      result.private_bytes = memory.PrivateUsage;
    } else result.error += " 读取内存失败 Win32=" + std::to_string(GetLastError());
    return result;
  }
};
inline std::wstring resource_summary(const ResourceSample& sample, bool detail = false) {
  if (!sample.error.empty()) return L"资源信息读取失败，详情见调试日志";
  wchar_t cpu[40]{};
  if (sample.cpu_ready) swprintf_s(cpu, L"%.1f%%", sample.cpu);
  else wcscpy_s(cpu, L"采样中…");
  wchar_t memory[120]{};
  swprintf_s(memory, L" · 物理内存 %.1f MiB", sample.working_set / 1048576.0);
  std::wstring text = L"本程序 CPU " + std::wstring(cpu) + memory;
  if (detail) {
    swprintf_s(memory, L" · 私有提交 %.1f MiB · PID=%lu", sample.private_bytes / 1048576.0, GetCurrentProcessId());
    text += memory;
  }
  return text;
}
}
