#pragma once
#ifndef WIN32_LEAN_AND_MEAN
#define WIN32_LEAN_AND_MEAN
#endif
#include <windows.h>
#include <optional>
#include <string>
#include <vector>
#include <deque>
#include <unordered_map>

namespace scan {
inline constexpr int port = 28461;
inline constexpr std::size_t max_bytes = 8192;
inline constexpr std::size_t max_units = 4096;
struct Frame { std::string id; std::wstring text; };
class DeliveryCache {
  struct Saved { std::wstring text; std::string result; };
  std::unordered_map<std::string, Saved> saved_;
  std::deque<std::string> order_;
 public:
  template<class Sender> std::pair<std::string, bool> submit(const Frame& frame, Sender sender) {
    const auto previous = saved_.find(frame.id);
    if (previous != saved_.end()) {
      return {previous->second.text == frame.text ? previous->second.result : "INVALID", false};
    }
    const auto result = sender(frame.text);
    saved_.emplace(frame.id, Saved{frame.text, result}); order_.push_back(frame.id);
    if (order_.size() > 1024) { saved_.erase(order_.front()); order_.pop_front(); }
    return {result, true};
  }
};

inline bool valid_id(const std::string& id) {
  if (id.size() != 36) return false;
  for (std::size_t i = 0; i < id.size(); ++i) {
    if (i == 8 || i == 13 || i == 18 || i == 23) {
      if (id[i] != '-') return false;
    } else if (!((id[i] >= '0' && id[i] <= '9') || (id[i] >= 'a' && id[i] <= 'f'))) return false;
  }
  return true;
}
inline int nibble(char c) {
  if (c >= '0' && c <= '9') return c - '0';
  if (c >= 'a' && c <= 'f') return c - 'a' + 10;
  return -1;
}
inline bool allowed_text(const std::wstring& text) {
  if (text.empty() || text.size() > max_units) return false;
  for (wchar_t c : text) {
    if (c < 32 || (c >= 127 && c <= 159) || c == 0x2028 || c == 0x2029) return false;
  }
  return true;
}
inline std::optional<Frame> decode(const std::string& line) {
  if (line.rfind("DATA ", 0) != 0 || line.size() < 43 || line[41] != ' ') return std::nullopt;
  const std::string id = line.substr(5, 36);
  const std::string hex = line.substr(42);
  if (!valid_id(id) || hex.empty() || hex.size() % 2 || hex.size() > max_bytes * 2) return std::nullopt;
  std::string bytes;
  bytes.reserve(hex.size() / 2);
  for (std::size_t i = 0; i < hex.size(); i += 2) {
    const int high = nibble(hex[i]), low = nibble(hex[i + 1]);
    if (high < 0 || low < 0) return std::nullopt;
    bytes.push_back(static_cast<char>(high * 16 + low));
  }
  const int size = MultiByteToWideChar(CP_UTF8, MB_ERR_INVALID_CHARS, bytes.data(),
    static_cast<int>(bytes.size()), nullptr, 0);
  if (size <= 0 || size > static_cast<int>(max_units)) return std::nullopt;
  std::wstring text(size, L'\0');
  if (MultiByteToWideChar(CP_UTF8, MB_ERR_INVALID_CHARS, bytes.data(), static_cast<int>(bytes.size()),
      text.data(), size) != size || !allowed_text(text)) return std::nullopt;
  return Frame{id, text};
}
inline std::vector<INPUT> input_events(const std::wstring& text) {
  std::vector<INPUT> result;
  result.reserve(text.size() * 2 + 2);
  for (wchar_t c : text) {
    INPUT event{};
    event.type = INPUT_KEYBOARD;
    event.ki.wScan = c;
    event.ki.dwFlags = KEYEVENTF_UNICODE;
    result.push_back(event);
    event.ki.dwFlags |= KEYEVENTF_KEYUP;
    result.push_back(event);
  }
  INPUT enter{};
  enter.type = INPUT_KEYBOARD;
  enter.ki.wVk = VK_RETURN;
  result.push_back(enter);
  enter.ki.dwFlags = KEYEVENTF_KEYUP;
  result.push_back(enter);
  return result;
}
inline std::string type_text(const std::wstring& text, bool enabled) {
  if (!enabled) return "PAUSED";
  if (!allowed_text(text)) return "INVALID";
  HWND target = GetForegroundWindow();
  DWORD process = 0;
  if (target) GetWindowThreadProcessId(target, &process);
  if (!target || !process || process == GetCurrentProcessId()) return "NO_TARGET";
  for (int key : {VK_SHIFT, VK_CONTROL, VK_MENU, VK_LWIN, VK_RWIN}) {
    if (GetAsyncKeyState(key) & 0x8000) return "NO_TARGET";
  }
  auto events = input_events(text);
  if (GetForegroundWindow() != target) return "NO_TARGET";
  const UINT count = static_cast<UINT>(events.size());
  return SendInput(count, events.data(), sizeof(INPUT)) == count ? "OK" : "PARTIAL";
}
}
