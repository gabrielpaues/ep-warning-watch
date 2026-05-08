#include "shake_detector.h"
#include <math.h>
#include <string.h>

#define MIN_SAMPLES 8
#define MAX_THRESHOLD 12.0f
#define MIN_THRESHOLD 3.0f

const shake_config_t SHAKE_DEFAULT_CONFIG = {
    .sensitivity = 0.5f,
    .sustain_seconds = 8.0f,
    .window_seconds = 2.0f,
    .cooldown_seconds = 60.0f,
};

float shake_detector_threshold(float sensitivity) {
    if (sensitivity < 0.0f) sensitivity = 0.0f;
    if (sensitivity > 1.0f) sensitivity = 1.0f;
    return MAX_THRESHOLD - sensitivity * (MAX_THRESHOLD - MIN_THRESHOLD);
}

void shake_detector_init(shake_detector_t *sd, const shake_config_t *cfg) {
    memset(sd, 0, sizeof(*sd));
    sd->config = cfg ? *cfg : SHAKE_DEFAULT_CONFIG;
}

void shake_detector_reset(shake_detector_t *sd) {
    sd->window_head = 0;
    sd->window_count = 0;
    sd->sustain_start_ns = 0;
    sd->peak_intensity = 0.0f;
}

void shake_detector_update_config(shake_detector_t *sd, const shake_config_t *cfg) {
    if (cfg) sd->config = *cfg;
}

static void window_push(shake_detector_t *sd, int64_t t_ns, float magnitude) {
    sd->window[sd->window_head].t_ns = t_ns;
    sd->window[sd->window_head].magnitude = magnitude;
    sd->window_head = (sd->window_head + 1) % SHAKE_WINDOW_CAPACITY;
    if (sd->window_count < SHAKE_WINDOW_CAPACITY) sd->window_count++;
}

// Compute rolling mean of samples in the most recent window_seconds.
// Drops too-old samples from the count by walking back from head.
static float window_mean(const shake_detector_t *sd, int64_t now_ns, int64_t window_ns,
                        int *out_used) {
    if (sd->window_count == 0) { *out_used = 0; return 0.0f; }
    double sum = 0.0;
    int used = 0;
    for (int i = 0; i < sd->window_count; ++i) {
        // Walk backwards from the most-recent sample.
        int idx = (sd->window_head - 1 - i + SHAKE_WINDOW_CAPACITY) % SHAKE_WINDOW_CAPACITY;
        int64_t age = now_ns - sd->window[idx].t_ns;
        if (age > window_ns) break;
        sum += sd->window[idx].magnitude;
        used++;
    }
    *out_used = used;
    return used > 0 ? (float)(sum / used) : 0.0f;
}

shake_detection_t shake_detector_on_gyro(shake_detector_t *sd, int64_t timestamp_ns,
                                         float wx, float wy, float wz) {
    shake_detection_t out = { .kind = SHAKE_DETECTION_IDLE };
    float magnitude = sqrtf(wx * wx + wy * wy + wz * wz);
    window_push(sd, timestamp_ns, magnitude);

    int64_t window_ns = (int64_t)(sd->config.window_seconds * 1e9f);
    int64_t sustain_ns = (int64_t)(sd->config.sustain_seconds * 1e9f);
    int64_t cooldown_ns = (int64_t)(sd->config.cooldown_seconds * 1e9f);

    int used = 0;
    float mean = window_mean(sd, timestamp_ns, window_ns, &used);
    if (used < MIN_SAMPLES) return out;

    float threshold = shake_detector_threshold(sd->config.sensitivity);

    if (mean >= threshold) {
        if (sd->sustain_start_ns == 0) sd->sustain_start_ns = timestamp_ns;
        if (mean > sd->peak_intensity) sd->peak_intensity = mean;
        int64_t sustained_for = timestamp_ns - sd->sustain_start_ns;
        bool past_cooldown = timestamp_ns - sd->last_trigger_ns >= cooldown_ns;
        if (sustained_for >= sustain_ns && past_cooldown) {
            sd->last_trigger_ns = timestamp_ns;
            out.kind = SHAKE_DETECTION_TRIGGER;
            out.peak_intensity = sd->peak_intensity;
            out.sustained_seconds = (float)sustained_for / 1e9f;
            sd->sustain_start_ns = 0;
            sd->peak_intensity = 0.0f;
        } else {
            out.kind = SHAKE_DETECTION_BUILDING;
            float progress = (float)sustained_for / (float)sustain_ns;
            if (progress < 0.0f) progress = 0.0f;
            if (progress > 1.0f) progress = 1.0f;
            out.progress = progress;
        }
    } else {
        sd->sustain_start_ns = 0;
        sd->peak_intensity = 0.0f;
    }
    return out;
}
