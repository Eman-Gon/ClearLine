#include "cancellation.h"
#include <atomic>
#include <cstdlib>
#include <iostream>
#include <thread>
#include <vector>

void check(bool condition) {
    if (!condition) std::abort();
}
int main() {
    clearline::Cancellation cancellation;
    check(!cancellation.cancelled(1));
    cancellation.cancel(5); // cancellation may precede nativeGenerate
    check(cancellation.cancelled(5));
    check(!cancellation.cancelled(6));
    cancellation.cancel(2); // late cancellation does not poison next request
    check(cancellation.cancelled(5));
    check(!cancellation.cancelled(6));
    check(!cancellation.cancelled(0));
    std::vector<std::thread> callers;
    for (int64_t i = 1; i <= 32; ++i) {
        callers.emplace_back([&, i] {
            for (int64_t j = 1; j <= 2000; ++j) cancellation.cancel(i * j);
        });
    }
    for (auto & thread : callers) thread.join();
    check(cancellation.cancelled(64000));
    check(!cancellation.cancelled(64001));
    std::cout << "Cancellation: pre-start, late and concurrent requests passed\n";
}
