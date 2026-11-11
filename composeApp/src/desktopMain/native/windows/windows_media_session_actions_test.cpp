#include "windows_media_session_actions.h"

#include <cassert>
#include <cstring>
#include <memory>

int main() {
    using namespace starlit;
    assert(mediaCommandForButton(0, false) == MediaCommand::Play);
    assert(mediaCommandForButton(1, false) == MediaCommand::Pause);
    assert(mediaCommandForButton(4, false) == MediaCommand::SeekForward);
    assert(mediaCommandForButton(5, false) == MediaCommand::SeekBackward);
    assert(mediaCommandForButton(6, false) == MediaCommand::None);
    assert(mediaCommandForButton(6, true) == MediaCommand::Next);
    assert(mediaCommandForButton(2, true) == MediaCommand::None);
    assert(mediaCommandForButton(7, true) == MediaCommand::None);
    assert(mediaPlayerEventName(MediaCommand::None) == nullptr);
    assert(std::strcmp(mediaPlayerEventName(MediaCommand::Play), "setPlaybackStateQuiet") == 0);
    assert(std::strcmp(mediaPlayerEventName(MediaCommand::Pause), "setPlaybackStateQuiet") == 0);
    assert(std::strcmp(mediaPlayerEventName(MediaCommand::SeekForward), "seekForward") == 0);
    assert(std::strcmp(mediaPlayerEventName(MediaCommand::SeekBackward), "seekBack") == 0);
    assert(std::strcmp(mediaPlayerEventName(MediaCommand::Next), "playNextEpisode") == 0);
    assert(std::strcmp(mediaPlayerEventName(MediaCommand::SeekTo), "scrubFinish") == 0);

    assert(mediaSeekMilliseconds(-1, 9000) == -1);
    assert(mediaSeekMilliseconds(50000000, 0) == -1);
    assert(mediaSeekMilliseconds(50000000, 9000) == 5000);
    assert(mediaSeekMilliseconds(150000000, 9000) == 9000);

    int calls = 0;
    MediaCommand last = MediaCommand::None;
    int64_t lastPosition = 0;
    auto gate = std::make_shared<MediaCommandGate>([&](MediaCommand command, int64_t ms) {
        ++calls;
        last = command;
        lastPosition = ms;
    });
    std::weak_ptr<MediaCommandGate> listener = gate;
    if (auto state = listener.lock()) state->emit(MediaCommand::Play);
    assert(calls == 1 && last == MediaCommand::Play);
    assert(!gate->nextPlayable() && gate->durationMs() == 0);
    gate->setNextPlayable(true);
    gate->setDurationMs(9000);
    assert(gate->nextPlayable() && gate->durationMs() == 9000);
    assert(mediaSeekMilliseconds(150000000, gate->durationMs()) == 9000);
    if (auto state = listener.lock()) state->emit(MediaCommand::SeekTo, 5000);
    assert(calls == 2 && last == MediaCommand::SeekTo && lastPosition == 5000);

    gate->close();
    if (auto state = listener.lock()) state->emit(MediaCommand::Next);
    assert(calls == 2);
    gate.reset();
    assert(listener.expired());
    return 0;
}
