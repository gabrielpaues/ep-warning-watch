#include "service.h"
#include "shake_detector.h"
#include "sap_provider.h"

#include <app.h>
#include <app_preference.h>
#include <dlog.h>
#include <math.h>
#include <sensor.h>
#include <service_app.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

#define LOG_TAG "EP_WARNING"

#define ACCEL_INTERVAL_MS         200    // ~5 Hz: cheap motion gate
#define ACCEL_BATCH_LATENCY_MS    5000   // SoC can sleep 5 s between batches
#define GYRO_INTERVAL_MS          20     // 50 Hz: detector sample rate
#define WAKE_GATE_M_S2            2.5f   // |a| - g threshold to escalate
#define IDLE_TIMEOUT_NS           15000000000LL // 15 s of below-gate motion drops back to idle
#define DEG_TO_RAD                0.01745329252f

typedef enum { STAGE_IDLE, STAGE_ACTIVE } stage_t;

static struct {
    bool running;
    stage_t stage;
    sensor_h accel_sensor;
    sensor_listener_h accel_listener;
    sensor_h gyro_sensor;
    sensor_listener_h gyro_listener;
    shake_detector_t detector;
    int64_t last_active_motion_ns;
} g_state;

static int64_t monotonic_ns(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (int64_t)ts.tv_sec * 1000000000LL + ts.tv_nsec;
}

static int64_t epoch_ms_now(void) {
    struct timespec ts;
    clock_gettime(CLOCK_REALTIME, &ts);
    return (int64_t)ts.tv_sec * 1000LL + ts.tv_nsec / 1000000LL;
}

static shake_config_t load_config_from_prefs(void) {
    shake_config_t cfg = SHAKE_DEFAULT_CONFIG;
    double v = 0.0;
    if (preference_get_double(PREF_SENSITIVITY, &v) == PREFERENCE_ERROR_NONE) {
        cfg.sensitivity = (float)v;
    }
    if (preference_get_double(PREF_SUSTAIN_SECONDS, &v) == PREFERENCE_ERROR_NONE) {
        cfg.sustain_seconds = (float)v;
    }
    return cfg;
}

static void register_idle_stage(void);
static void register_active_stage(void);
static void unregister_all_sensors(void);

static void on_alarm_triggered(const shake_detection_t *detection) {
    alarm_payload_t payload;
    snprintf(payload.id, sizeof(payload.id), "%lld", (long long)epoch_ms_now());
    payload.triggered_at_epoch_ms = epoch_ms_now();
    payload.peak_intensity = detection->peak_intensity;
    payload.sustained_seconds = detection->sustained_seconds;

    bool ok = sap_provider_send_alarm(&payload);
    dlog_print(DLOG_INFO, LOG_TAG, "alarm fired peak=%.2f sustained=%.1fs delivered=%d",
               payload.peak_intensity, payload.sustained_seconds, ok ? 1 : 0);
    preference_set_double(PREF_LAST_ALARM_TIME, (double)payload.triggered_at_epoch_ms);
}

static void accel_event_cb(sensor_h sensor, sensor_event_s *event, void *user_data) {
    (void)sensor; (void)user_data;
    if (g_state.stage != STAGE_IDLE || event->value_count < 3) return;
    float ax = event->values[0];
    float ay = event->values[1];
    float az = event->values[2];
    float linear = sqrtf(ax * ax + ay * ay + az * az) - 9.80665f;
    if (fabsf(linear) > WAKE_GATE_M_S2) {
        register_active_stage();
    }
}

static void gyro_event_cb(sensor_h sensor, sensor_event_s *event, void *user_data) {
    (void)sensor; (void)user_data;
    if (g_state.stage != STAGE_ACTIVE || event->value_count < 3) return;
    // Tizen gyroscope reports deg/s; the detector wants rad/s.
    float wx = event->values[0] * DEG_TO_RAD;
    float wy = event->values[1] * DEG_TO_RAD;
    float wz = event->values[2] * DEG_TO_RAD;
    int64_t now = monotonic_ns();
    shake_detection_t det = shake_detector_on_gyro(&g_state.detector, now, wx, wy, wz);
    switch (det.kind) {
    case SHAKE_DETECTION_IDLE:
        if (now - g_state.last_active_motion_ns > IDLE_TIMEOUT_NS) {
            register_idle_stage();
        }
        break;
    case SHAKE_DETECTION_BUILDING:
        g_state.last_active_motion_ns = now;
        break;
    case SHAKE_DETECTION_TRIGGER:
        g_state.last_active_motion_ns = now;
        on_alarm_triggered(&det);
        break;
    }
}

static void unregister_all_sensors(void) {
    if (g_state.accel_listener) {
        sensor_listener_stop(g_state.accel_listener);
        sensor_destroy_listener(g_state.accel_listener);
        g_state.accel_listener = NULL;
    }
    if (g_state.gyro_listener) {
        sensor_listener_stop(g_state.gyro_listener);
        sensor_destroy_listener(g_state.gyro_listener);
        g_state.gyro_listener = NULL;
    }
}

static void register_idle_stage(void) {
    unregister_all_sensors();
    if (sensor_get_default_sensor(SENSOR_ACCELEROMETER, &g_state.accel_sensor) != SENSOR_ERROR_NONE) {
        dlog_print(DLOG_ERROR, LOG_TAG, "no accelerometer");
        return;
    }
    if (sensor_create_listener(g_state.accel_sensor, &g_state.accel_listener) != SENSOR_ERROR_NONE) return;
    sensor_listener_set_event_cb(g_state.accel_listener, ACCEL_INTERVAL_MS, accel_event_cb, NULL);
    sensor_listener_set_max_batch_latency(g_state.accel_listener, ACCEL_BATCH_LATENCY_MS);
    sensor_listener_set_attribute_int(g_state.accel_listener, SENSOR_ATTRIBUTE_PAUSE_POLICY, SENSOR_PAUSE_NONE);
    sensor_listener_start(g_state.accel_listener);
    g_state.stage = STAGE_IDLE;
    dlog_print(DLOG_INFO, LOG_TAG, "stage=idle (accel batched)");
}

static void register_active_stage(void) {
    unregister_all_sensors();
    if (sensor_get_default_sensor(SENSOR_GYROSCOPE, &g_state.gyro_sensor) != SENSOR_ERROR_NONE) {
        dlog_print(DLOG_ERROR, LOG_TAG, "no gyroscope; staying idle");
        register_idle_stage();
        return;
    }
    if (sensor_create_listener(g_state.gyro_sensor, &g_state.gyro_listener) != SENSOR_ERROR_NONE) {
        register_idle_stage();
        return;
    }
    sensor_listener_set_event_cb(g_state.gyro_listener, GYRO_INTERVAL_MS, gyro_event_cb, NULL);
    sensor_listener_set_max_batch_latency(g_state.gyro_listener, 0);
    sensor_listener_set_attribute_int(g_state.gyro_listener, SENSOR_ATTRIBUTE_PAUSE_POLICY, SENSOR_PAUSE_NONE);
    sensor_listener_start(g_state.gyro_listener);
    shake_detector_reset(&g_state.detector);
    g_state.last_active_motion_ns = monotonic_ns();
    g_state.stage = STAGE_ACTIVE;
    dlog_print(DLOG_INFO, LOG_TAG, "stage=active (gyro live)");
}

bool detector_start(void) {
    if (g_state.running) return true;
    shake_config_t cfg = load_config_from_prefs();
    shake_detector_init(&g_state.detector, &cfg);
    register_idle_stage();
    g_state.running = true;
    preference_set_int(PREF_MONITORING_ON, 1);
    return true;
}

void detector_stop(void) {
    unregister_all_sensors();
    g_state.running = false;
    preference_set_int(PREF_MONITORING_ON, 0);
    dlog_print(DLOG_INFO, LOG_TAG, "detector stopped");
}

bool detector_is_running(void) { return g_state.running; }

void detector_apply_config(const shake_config_t *cfg) {
    shake_detector_update_config(&g_state.detector, cfg);
}

// --- service-application lifecycle ---

static bool app_create(void *user_data) {
    (void)user_data;
    sap_provider_start();
    int monitoring = 0;
    if (preference_get_int(PREF_MONITORING_ON, &monitoring) == PREFERENCE_ERROR_NONE && monitoring) {
        detector_start();
    }
    return true;
}

static void app_control(app_control_h app_control, void *user_data) {
    (void)user_data;
    char *op = NULL;
    if (app_control_get_operation(app_control, &op) == APP_CONTROL_ERROR_NONE && op) {
        if (strcmp(op, APP_CONTROL_OP_START) == 0) {
            detector_start();
        } else if (strcmp(op, APP_CONTROL_OP_STOP) == 0) {
            detector_stop();
        } else if (strcmp(op, APP_CONTROL_OP_CONFIG) == 0) {
            shake_config_t cfg = load_config_from_prefs();
            detector_apply_config(&cfg);
        }
        free(op);
    }
}

static void app_terminate(void *user_data) {
    (void)user_data;
    detector_stop();
    sap_provider_stop();
}

int main(int argc, char *argv[]) {
    service_app_lifecycle_callback_s cb = {0};
    cb.create = app_create;
    cb.terminate = app_terminate;
    cb.app_control = app_control;
    return service_app_main(argc, argv, &cb, NULL);
}
