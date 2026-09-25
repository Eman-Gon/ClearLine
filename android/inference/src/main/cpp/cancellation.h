#pragma once
#include <atomic>
#include <cstdint>

namespace clearline {
// Request IDs are positive and strictly increasing for the life of a handle.
// Keep pre-start cancellations. A delayed cancellation of an older request must
// never clear a newer cancellation or cancel a subsequent request.
class Cancellation {
public:
    void cancel(int64_t id) noexcept {
        auto old = cancelled_through_.load(std::memory_order_acquire);
        while (old < id && !cancelled_through_.compare_exchange_weak(
                old, id, std::memory_order_acq_rel, std::memory_order_acquire)) {}
    }
    bool cancelled(int64_t id) const noexcept {
        return id > 0 && cancelled_through_.load(std::memory_order_acquire) >= id;
    }
private:
    std::atomic<int64_t> cancelled_through_{0};
};
}  // namespace clearline
