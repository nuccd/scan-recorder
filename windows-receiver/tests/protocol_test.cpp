#include "../src/protocol.hpp"
#include <iostream>
#include <stdexcept>

namespace {
int checks = 0;
void check(bool pass) { ++checks; if (!pass) throw std::runtime_error("check " + std::to_string(checks)); }
const std::string id = "12345678-1234-1234-1234-123456789abc";
std::string packet(const std::string& hex) { return "DATA " + id + " " + hex; }
}
int main() {
  try {
    auto number = scan::decode(packet("303031323334353637383930313233343536"));
    check(number && number->text == L"001234567890123456" && number->id == id);
    auto chinese = scan::decode(packet("e4b8ade69687f09f9880"));
    check(chinese && chinese->text == L"中文\U0001f600");
    check(!scan::decode(packet("")));
    check(!scan::decode(packet("0")));
    check(!scan::decode(packet("zz")));
    check(!scan::decode(packet("c0af")));
    check(!scan::decode(packet("eda080")));
    check(!scan::decode(packet("00")));
    check(!scan::decode(packet("610962")));
    check(!scan::decode(packet("610a62")));
    check(!scan::decode(packet("610d62")));
    check(!scan::decode(packet("7f")));
    check(!scan::decode(packet("c285")));
    check(!scan::decode(packet("e280a8")));
    check(!scan::decode(packet("e280a9")));
    check(!scan::decode("DATA invalid 31"));
    check(!scan::decode(packet(std::string(16386, '3'))));
    check(!scan::allowed_text(std::wstring(4097, L'a')));
    check(scan::type_text(L"123", false) == "PAUSED");
    const auto events = scan::input_events(L"001中\U0001f600");
    check(events.size() == 14);
    check(events[0].ki.wScan == L'0' && events[0].ki.wVk == 0);
    check(events[1].ki.dwFlags == (KEYEVENTF_UNICODE | KEYEVENTF_KEYUP));
    check(events[events.size() - 2].ki.wVk == VK_RETURN);
    check(events.back().ki.dwFlags == KEYEVENTF_KEYUP);
    scan::DeliveryCache cache;
    int calls = 0;
    auto sender = [&calls](const auto&) { ++calls; return "OK"; };
    check(cache.submit(*number, sender) == std::make_pair(std::string("OK"), true));
    check(cache.submit(*number, sender) == std::make_pair(std::string("OK"), false));
    check(calls == 1);
    auto conflicting = *number; conflicting.text = L"different";
    check(cache.submit(conflicting, sender).first == "INVALID");
    check(calls == 1);
    auto another = *number; another.id[0] = 'a';
    check(cache.submit(another, sender).second);
    check(calls == 2);
    auto partial = *number; partial.id[0] = 'b';
    check(cache.submit(partial, [](const auto&) { return "PARTIAL"; }).first == "PARTIAL");
    check(cache.submit(partial, sender).first == "PARTIAL");
    check(calls == 2);
    std::cout << "PASS " << checks << " protocol and keyboard-event checks\n";
    return 0;
  } catch (const std::exception& error) { std::cerr << "FAIL " << error.what() << '\n'; return 1; }
}
