//
// Created by greensun on 2020/4/7.
//
#include <ThreadPool.h>

ThreadPool::ThreadPool(size_t threads, int max) : ThreadPool(threads) {
    this->max = max;
}


// the constructor just launches some amount of workers
ThreadPool::ThreadPool(size_t threads) {
    if (stop.load()) {
        LOGE("stop = true");
    } else {
        LOGE("stop = false");
    }
    for (size_t i = 0; i < threads; ++i)
        workers.emplace_back(
                [this, i] {
                    while (true) {
                        std::function<void()> task;
                        {
                            std::unique_lock<std::mutex> lock(this->queue_mutex);
                            // 任务队列为空时阻塞
                            this->condition.wait(lock, [this] {
                                //LOGE("tasks size:%d", (int)tasks.size());
                                return this->stop.load() || !this->tasks.empty();
                            });
                        if (this->stop.load() || this->tasks.empty())
                            break;
                            task = std::move(this->tasks.front());
                            this->tasks.pop();
                        }
                        task();
                    }
                }
        );
}

// add new work item to the pool
void ThreadPool::enqueue(std::function<void()> &&task) {
    if (stop.load()){
        LOGE("线程池已经结束了");
    }
        //throw std::runtime_error("enqueue on stopped ThreadPool1");
    else{
        std::unique_lock<std::mutex> lock(queue_mutex);
        if (max != -1) {
            if (tasks.size() > max) {
                LOGE("too many tasks, clear");
                std::queue<std::function<void()>> empty;
                tasks.swap(empty);
            }
        }
        tasks.emplace(task);
    }
    condition.notify_one();
}

// the destructor joins all threads
ThreadPool::~ThreadPool() {
    LOGE("~ThreadPool stop = true");
    {
        std::unique_lock<std::mutex> lock(queue_mutex);
        stop = true;
    }
    condition.notify_all();
    for (std::thread &worker: workers)
        worker.join();
}
