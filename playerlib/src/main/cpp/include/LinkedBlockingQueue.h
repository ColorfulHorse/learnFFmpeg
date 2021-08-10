//
// Created by hunny on 2020/5/27.
//

#ifndef LYJPLAYER_BLOCKINGQUEUE_H
#define LYJPLAYER_BLOCKINGQUEUE_H

#include <list>
#include <mutex>
#include <condition_variable>

/**
 * 读写分离阻塞队列
 * @tparam T
 */
template<typename T>
class LinkedBlockingQueue {
private:
    std::list<T> elements;
    std::mutex poll_mtx, offer_mtx;
    std::condition_variable cond_empty, cond_full;
    size_t max;
    std::atomic_size_t count;
public:
    LinkedBlockingQueue<T>() : max(-1), count(0) {

    };

    LinkedBlockingQueue<T>(const size_t size) : max(size), count(0) {

    };

    T poll() {
        T e;
        {
            std::unique_lock<std::mutex> lock(poll_mtx);
            cond_empty.wait(lock, [this] { return !empty(); });
            e = elements.front();
            elements.pop_front();
            count--;
        }
        cond_full.notify_one();
        return e;
    };

    void offer(T data) {
        {
            std::unique_lock<std::mutex> lock(offer_mtx);
            if (max > 0) {
                // predicate false阻塞
                cond_full.wait(lock, [this] { return count.load() < max; });
            }
            elements.emplace_back(std::move(data));
            count++;
        }
        cond_empty.notify_one();
    };

    size_t size() {
        return count.load();
    };

    bool empty() {
        return count.load() == 0;
    };

    void clear() {
        std::lock_guard<std::mutex> lck(poll_mtx);
        std::lock_guard<std::mutex> lock(offer_mtx);
        std::list<T> empty;
        elements.swap(empty);
        count = 0;
        cond_full.notify_all();
    };
};

#endif //LYJPLAYER_BLOCKINGQUEUE_H
