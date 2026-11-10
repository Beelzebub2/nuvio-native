#pragma once

#include "windows_media_session_actions.h"

#include <windows.h>
#include <SystemMediaTransportControlsInterop.h>
#include <winrt/base.h>
#include <winrt/Windows.Foundation.h>
#include <winrt/Windows.Media.h>

#include <chrono>
#include <memory>
#include <string>

namespace starlit {

// Registered on the native player's STA while its AWT-owned top-level HWND is alive.
// System media controls are independent of the embedded video controls WebView.
class WindowsMediaSession {
public:
    bool start(HWND rootWindow, std::function<void(MediaCommand, int64_t)> onCommand) noexcept {
        if (!rootWindow || !IsWindow(rootWindow)) return false;
        DWORD windowProcessId = 0;
        GetWindowThreadProcessId(rootWindow, &windowProcessId);
        if (windowProcessId != GetCurrentProcessId() ||
            (GetWindowLongPtrW(rootWindow, GWL_STYLE) & WS_CHILD) != 0) {
            return false;
        }

        try {
            winrt::init_apartment(winrt::apartment_type::single_threaded);
            apartmentInitialized_ = true;
            auto activation = winrt::get_activation_factory<
                winrt::Windows::Media::SystemMediaTransportControls>();
            auto interop = activation.as<ISystemMediaTransportControlsInterop>();
            winrt::check_hresult(interop->GetForWindow(
                rootWindow,
                winrt::guid_of<winrt::Windows::Media::SystemMediaTransportControls>(),
                winrt::put_abi(controls_)));

            controls_.IsPlayEnabled(true);
            controls_.IsPauseEnabled(true);
            controls_.IsNextEnabled(false);
            controls_.IsPreviousEnabled(false);
            controls_.IsFastForwardEnabled(true);
            controls_.IsRewindEnabled(true);
            controls_.IsStopEnabled(false);

            auto updater = controls_.DisplayUpdater();
            updater.Type(winrt::Windows::Media::MediaPlaybackType::Video);
            updater.VideoProperties().Title(L"Starlit");
            updater.Update();

            gate_ = std::make_shared<MediaCommandGate>(std::move(onCommand));
            auto weakGate = std::weak_ptr<MediaCommandGate>(gate_);
            buttonToken_ = controls_.ButtonPressed(
                [weakGate](
                    winrt::Windows::Media::SystemMediaTransportControls const&,
                    winrt::Windows::Media::SystemMediaTransportControlsButtonPressedEventArgs const& args
                ) {
                    if (auto gate = weakGate.lock()) {
                        gate->emit(mediaCommandForButton(static_cast<int>(args.Button()), gate->nextPlayable()));
                    }
                });
            hasButtonToken_ = true;

            try {
                positionToken_ = controls_.PlaybackPositionChangeRequested(
                    [weakGate](
                        winrt::Windows::Media::SystemMediaTransportControls const&,
                        winrt::Windows::Media::PlaybackPositionChangeRequestedEventArgs const& args
                    ) {
                        if (auto gate = weakGate.lock()) {
                            const int64_t positionMs = mediaSeekMilliseconds(
                                args.RequestedPlaybackPosition().count(), gate->durationMs());
                            if (positionMs >= 0) gate->emit(MediaCommand::SeekTo, positionMs);
                        }
                    });
                hasPositionToken_ = true;
            } catch (winrt::hresult_error const&) {
                // Older Windows versions can still provide play/pause and skip controls.
            }

            controls_.PlaybackStatus(winrt::Windows::Media::MediaPlaybackStatus::Changing);
            controls_.IsEnabled(true);
            return true;
        } catch (winrt::hresult_error const&) {
            OutputDebugStringW(L"Starlit Windows SMTC activation failed; in-app playback remains available.\n");
            stop();
            return false;
        } catch (...) {
            stop();
            return false;
        }
    }

    void updateMetadata(std::wstring const& title, std::wstring const& subtitle) noexcept {
        if (!controls_) return;
        try {
            auto updater = controls_.DisplayUpdater();
            updater.Type(winrt::Windows::Media::MediaPlaybackType::Video);
            auto properties = updater.VideoProperties();
            properties.Title(title.empty() ? L"Starlit" : winrt::hstring(title.substr(0, 256)));
            properties.Subtitle(winrt::hstring(subtitle.substr(0, 256)));
            updater.Update();
        } catch (winrt::hresult_error const&) {
            // Keep playback active if the shell media widget becomes unavailable.
        }
    }

    void updateNextPlayable(bool available) noexcept {
        if (gate_) gate_->setNextPlayable(available);
        if (!controls_) return;
        try {
            controls_.IsNextEnabled(available);
        } catch (winrt::hresult_error const&) {}
    }

    void sync(int64_t durationMs, int64_t positionMs, bool paused, bool loading, bool ended) noexcept {
        if (!controls_) return;
        constexpr int64_t kMaximumDurationMs = 14LL * 24 * 60 * 60 * 1000;
        const int64_t duration = durationMs > 0 && durationMs <= kMaximumDurationMs ? durationMs : 0;
        if (gate_) gate_->setDurationMs(duration);
        const auto status = ended
            ? winrt::Windows::Media::MediaPlaybackStatus::Stopped
            : loading
                ? winrt::Windows::Media::MediaPlaybackStatus::Changing
                : paused
                    ? winrt::Windows::Media::MediaPlaybackStatus::Paused
                    : winrt::Windows::Media::MediaPlaybackStatus::Playing;
        auto now = std::chrono::steady_clock::now();
        const bool statusChanged = status != lastStatus_;
        const bool durationChanged = duration != lastDurationMs_;
        if (!statusChanged && !durationChanged &&
            now - lastTimelineUpdate_ < std::chrono::milliseconds(1500)) return;

        try {
            if (statusChanged) controls_.PlaybackStatus(status);
            if (duration > 0 && hasPositionToken_) {
                auto timeline = winrt::Windows::Media::SystemMediaTransportControlsTimelineProperties();
                auto start = winrt::Windows::Foundation::TimeSpan{0};
                auto end = winrt::Windows::Foundation::TimeSpan{duration * 10000};
                auto position = winrt::Windows::Foundation::TimeSpan{
                    (positionMs < 0 ? 0 : positionMs > duration ? duration : positionMs) * 10000};
                timeline.StartTime(start);
                timeline.EndTime(end);
                timeline.MinSeekTime(start);
                timeline.MaxSeekTime(end);
                timeline.Position(position);
                controls_.UpdateTimelineProperties(timeline);
            }
            lastStatus_ = status;
            lastDurationMs_ = duration;
            lastTimelineUpdate_ = now;
        } catch (winrt::hresult_error const&) {
            // System shell is best effort, and must never stop video rendering.
        }
    }

    void stop() noexcept {
        if (gate_) gate_->close();
        if (controls_) {
            try {
                if (hasButtonToken_) controls_.ButtonPressed(buttonToken_);
                if (hasPositionToken_) controls_.PlaybackPositionChangeRequested(positionToken_);
            } catch (winrt::hresult_error const&) {}
            try {
                controls_.PlaybackStatus(winrt::Windows::Media::MediaPlaybackStatus::Closed);
            } catch (winrt::hresult_error const&) {}
            try {
                controls_.IsEnabled(false);
            } catch (winrt::hresult_error const&) {}
            controls_ = nullptr;
        }
        hasButtonToken_ = false;
        hasPositionToken_ = false;
        gate_.reset();
        if (apartmentInitialized_) {
            winrt::uninit_apartment();
            apartmentInitialized_ = false;
        }
    }

    ~WindowsMediaSession() { stop(); }

private:
    winrt::Windows::Media::SystemMediaTransportControls controls_{nullptr};
    std::shared_ptr<MediaCommandGate> gate_;
    winrt::event_token buttonToken_{};
    winrt::event_token positionToken_{};
    bool hasButtonToken_ = false;
    bool hasPositionToken_ = false;
    bool apartmentInitialized_ = false;
    winrt::Windows::Media::MediaPlaybackStatus lastStatus_ =
        winrt::Windows::Media::MediaPlaybackStatus::Closed;
    int64_t lastDurationMs_ = 0;
    std::chrono::steady_clock::time_point lastTimelineUpdate_{};
};

} // namespace starlit
