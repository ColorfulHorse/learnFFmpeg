//
// Created by green sun on 2020/3/15.
//

#ifndef LYJPLAYER_SAFEQUEUE_H
#define LYJPLAYER_SAFEQUEUE_H

#include <queue>
#include <thread>
#include <mutex>

using namespace std;

template<class T>
class SafeQueue {
public:
    std::queue<T> queue_;
    SafeQueue<T>() = default;
    void pop();
    T front();
    void push(T data);
    bool empty();
    int size();
private:
    mutex mtx;
};

template<class T>
T SafeQueue<T>::front() {
    lock_guard<mutex> lck(mtx);
    return queue_.front();
}

template<class T>
void SafeQueue<T>::pop() {
    lock_guard<mutex> lck(mtx);
    queue_.pop();
}

template<class T>
void SafeQueue<T>::push(T data) {
    lock_guard<mutex> lck(mtx);
    queue_.push(data);
}

template<class T>
bool SafeQueue<T>::empty() {
    lock_guard<mutex> lck(mtx);
    return queue_.empty();
}

template<class T>
int SafeQueue<T>::size() {
    lock_guard<mutex> lck(mtx);
    return queue_.size();
}

#endif //LYJPLAYER_SAFEQUEUE_H
