//
// Created by hunny on 2020/6/22.
//

#ifndef LEARNFFMPEG_TIMER_H
#define LEARNFFMPEG_TIMER_H
#include <iostream>
#include <thread>
#include <chrono>

class Timer {
    bool clear = false;

public:
    void setInterval(std::function<void()> && function, int interval) {
        this->clear = false;
        std::thread t([=]() {
            while (true) {
                if (this->clear) return;
                chrono::system_clock::time_point start = chrono::system_clock::now();
                function();
                chrono::system_clock::time_point end = chrono::system_clock::now();
                int time = interval - chrono::duration_cast<chrono::milliseconds>(end - start).count();
                if (this->clear) return;
                if (time > 0) {
                    LOGE("sleep for %d milliseconds", time);
                    std::this_thread::sleep_for(chrono::milliseconds(time));
                }
            }
        });
        t.detach();
    }

    void stop(){
        this->clear = true;
    }
};

#endif //LEARNFFMPEG_TIMER_H
