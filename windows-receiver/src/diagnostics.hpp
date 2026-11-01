#pragma once
#include "adb_process.hpp"
#include <deque>
#include <sstream>

namespace scan {
inline std::wstring wide(const std::string& text) {
  const int size = MultiByteToWideChar(CP_UTF8, 0, text.data(), static_cast<int>(text.size()), nullptr, 0);
  std::wstring result(size, L'\0');
  if (size) MultiByteToWideChar(CP_UTF8, 0, text.data(), static_cast<int>(text.size()), result.data(), size);
  return result;
}
class DiagnosticLog {
  std::mutex mutex_;
  std::deque<std::wstring> entries_;
  std::size_t units_ = 0;
  HWND window_ = nullptr;
  UINT message_ = 0;
 public:
  void attach(HWND window, UINT message) {
    std::lock_guard lock(mutex_);
    window_ = window; message_ = message;
  }
  void write(const std::string& text) {
    SYSTEMTIME time{}; GetLocalTime(&time);
    wchar_t stamp[80]{};
    swprintf_s(stamp, L"[%02u:%02u:%02u.%03u T%lu] ", time.wHour, time.wMinute, time.wSecond,
      time.wMilliseconds, GetCurrentThreadId());
    auto entry = std::wstring(stamp) + wide(text);
    // Keep text bounded even if a faulty ADB floods stdout. No scan payload is logged.
    if (entry.size() > 20000) entry = entry.substr(0, 20000) + L"\r\n[输出已截断]";
    entry += L"\r\n";
    std::lock_guard lock(mutex_);
    units_ += entry.size(); entries_.push_back(std::move(entry));
    while (entries_.size() > 400 || units_ > 120000) {
      units_ -= entries_.front().size(); entries_.pop_front();
    }
    if (window_) PostMessageW(window_, message_, 0, 0);
  }
  std::wstring snapshot() {
    std::lock_guard lock(mutex_);
    std::wstring result;
    result.reserve(units_);
    for (const auto& entry : entries_) result += entry;
    return result;
  }
  void clear() {
    std::lock_guard lock(mutex_);
    entries_.clear(); units_ = 0;
    if (window_) PostMessageW(window_, message_, 0, 0);
  }
};
}
