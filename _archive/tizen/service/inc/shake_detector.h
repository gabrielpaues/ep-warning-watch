#ifndef EP_WARNING_SHAKE_DETECTOR_H
#define EP_WARNING_SHAKE_DETECTOR_H

#include <stdint.h>
#include <stdbool.h>

// Maximum samples held in the rolling window. At 50 Hz over 2 s = 100 samples; round up.
#define SHAKE_WINDOW_CAPACITY 256

typedef struct {
    float sensitivity;       // 0..1 (higher = more sensitive)
    float sustain_seconds;   // sustained-shake duration to trigger
    float window_seconds;    // rolling-mean window size
    float cooldown_seconds;  // suppress repeats this long after firing
} shake_config_t;

typedef enum {
    SHAKE_DETECTION_IDLE,
    SHAKE_DETECTION_BUILDING,
    SHAKE_DETECTION_TRIGGER,
} shake_detection_kind_t;

typedef struct {
    shake_detection_kind_t kind;
    float progress;          // 0..1, valid for BUILDING
    float peak_intensity;    // rad/s, valid for TRIGGER
    float sustained_seconds; // valid for TRIGGER
} shake_detection_t;

typedef struct {
    int64_t t_ns;
    float magnitude;
} shake_sample_t;

typedef struct {
    shake_config_t config;
    shake_sample_t window[SHAKE_WINDOW_CAPACITY];
    int window_head;     // next-write index
    int window_count;
    int64_t sustain_start_ns;
    int64_t last_trigger_ns;
    float peak_intensity;
} shake_detector_t;

void shake_detector_init(shake_detector_t *sd, const shake_config_t *cfg);
void shake_detector_reset(shake_detector_t *sd);
void shake_detector_update_config(shake_detector_t *sd, const shake_config_t *cfg);

// Map sensitivity (0..1) -> threshold (rad/s). 0 -> 12, 1 -> 3.
float shake_detector_threshold(float sensitivity);

// Feed one gyroscope sample (rad/s). Returns the resulting detection state.
shake_detection_t shake_detector_on_gyro(shake_detector_t *sd, int64_t timestamp_ns,
                                         float wx, float wy, float wz);

extern const shake_config_t SHAKE_DEFAULT_CONFIG;

#endif // EP_WARNING_SHAKE_DETECTOR_H
