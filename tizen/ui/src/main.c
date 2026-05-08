#include "ui.h"

#include <Elementary.h>
#include <app.h>
#include <app_control.h>
#include <app_preference.h>
#include <dlog.h>
#include <stdio.h>
#include <string.h>

#define LOG_TAG "EP_WARNING_UI"

#define APP_ID_SERVICE        "com.epwarning.watch.service"
#define OP_START              "com.epwarning.watch.START"
#define OP_STOP               "com.epwarning.watch.STOP"
#define OP_CONFIG             "com.epwarning.watch.CONFIG"

#define PREF_SENSITIVITY      "sensitivity"
#define PREF_SUSTAIN_SECONDS  "sustain_seconds"
#define PREF_MONITORING_ON    "monitoring_on"

static void send_app_control(const char *op) {
    app_control_h h = NULL;
    if (app_control_create(&h) != APP_CONTROL_ERROR_NONE) return;
    app_control_set_app_id(h, APP_ID_SERVICE);
    app_control_set_operation(h, op);
    app_control_send_launch_request(h, NULL, NULL);
    app_control_destroy(h);
}

static void on_toggle_click(void *data, Evas_Object *obj, void *event_info) {
    (void)obj; (void)event_info;
    appdata_t *ad = data;
    ad->monitoring = !ad->monitoring;
    preference_set_int(PREF_MONITORING_ON, ad->monitoring ? 1 : 0);
    send_app_control(ad->monitoring ? OP_START : OP_STOP);
    ui_refresh_status(ad);
}

static void on_sensitivity_change(void *data, Evas_Object *obj, void *event_info) {
    (void)event_info;
    appdata_t *ad = data;
    double v = elm_slider_value_get(obj);
    preference_set_double(PREF_SENSITIVITY, v);
    char buf[32];
    snprintf(buf, sizeof(buf), "Sensitivity %d%%", (int)(v * 100));
    elm_object_text_set(ad->sensitivity_label, buf);
    send_app_control(OP_CONFIG);
}

void ui_refresh_status(appdata_t *ad) {
    elm_object_text_set(ad->toggle_button, ad->monitoring ? "Stop" : "Start");
    elm_object_text_set(ad->status_label, ad->monitoring ? "Monitoring" : "Idle");
}

static void win_back_cb(void *data, Evas_Object *obj, void *event_info) {
    (void)obj; (void)event_info;
    appdata_t *ad = data;
    elm_win_lower(ad->win);
}

void ui_create(appdata_t *ad) {
    ad->win = elm_win_util_standard_add("ep_warning", "EP Warning");
    elm_win_autodel_set(ad->win, EINA_TRUE);
    eext_object_event_callback_add(ad->win, EEXT_CALLBACK_BACK, win_back_cb, ad);

    ad->conform = elm_conformant_add(ad->win);
    evas_object_size_hint_weight_set(ad->conform, EVAS_HINT_EXPAND, EVAS_HINT_EXPAND);
    elm_win_resize_object_add(ad->win, ad->conform);
    evas_object_show(ad->conform);

    Evas_Object *box = elm_box_add(ad->conform);
    elm_box_padding_set(box, 0, 8);
    evas_object_size_hint_weight_set(box, EVAS_HINT_EXPAND, EVAS_HINT_EXPAND);
    elm_object_content_set(ad->conform, box);
    evas_object_show(box);

    ad->status_label = elm_label_add(box);
    elm_object_text_set(ad->status_label, "Idle");
    evas_object_size_hint_weight_set(ad->status_label, EVAS_HINT_EXPAND, 0.0);
    evas_object_size_hint_align_set(ad->status_label, EVAS_HINT_FILL, 0.5);
    elm_box_pack_end(box, ad->status_label);
    evas_object_show(ad->status_label);

    ad->toggle_button = elm_button_add(box);
    elm_object_text_set(ad->toggle_button, "Start");
    evas_object_size_hint_weight_set(ad->toggle_button, EVAS_HINT_EXPAND, 0.0);
    evas_object_size_hint_align_set(ad->toggle_button, EVAS_HINT_FILL, 0.5);
    evas_object_smart_callback_add(ad->toggle_button, "clicked", on_toggle_click, ad);
    elm_box_pack_end(box, ad->toggle_button);
    evas_object_show(ad->toggle_button);

    ad->sensitivity_label = elm_label_add(box);
    elm_object_text_set(ad->sensitivity_label, "Sensitivity 50%");
    elm_box_pack_end(box, ad->sensitivity_label);
    evas_object_show(ad->sensitivity_label);

    ad->sensitivity_slider = elm_slider_add(box);
    elm_slider_min_max_set(ad->sensitivity_slider, 0.0, 1.0);
    elm_slider_step_set(ad->sensitivity_slider, 0.1);
    double saved = 0.5;
    preference_get_double(PREF_SENSITIVITY, &saved);
    elm_slider_value_set(ad->sensitivity_slider, saved);
    evas_object_size_hint_weight_set(ad->sensitivity_slider, EVAS_HINT_EXPAND, 0.0);
    evas_object_size_hint_align_set(ad->sensitivity_slider, EVAS_HINT_FILL, 0.5);
    evas_object_smart_callback_add(ad->sensitivity_slider, "delay,changed",
                                   on_sensitivity_change, ad);
    elm_box_pack_end(box, ad->sensitivity_slider);
    evas_object_show(ad->sensitivity_slider);

    int on = 0;
    preference_get_int(PREF_MONITORING_ON, &on);
    ad->monitoring = on != 0;
    ui_refresh_status(ad);

    char buf[32];
    snprintf(buf, sizeof(buf), "Sensitivity %d%%", (int)(saved * 100));
    elm_object_text_set(ad->sensitivity_label, buf);

    evas_object_show(ad->win);
}

static bool app_create(void *user_data) {
    appdata_t *ad = user_data;
    ui_create(ad);
    return true;
}

static void app_pause(void *user_data) { (void)user_data; }
static void app_resume(void *user_data) {
    appdata_t *ad = user_data;
    int on = 0;
    preference_get_int(PREF_MONITORING_ON, &on);
    ad->monitoring = on != 0;
    ui_refresh_status(ad);
}
static void app_terminate(void *user_data) { (void)user_data; }

int main(int argc, char *argv[]) {
    appdata_t ad = {0};
    ui_app_lifecycle_callback_s cb = {0};
    cb.create = app_create;
    cb.terminate = app_terminate;
    cb.pause = app_pause;
    cb.resume = app_resume;
    return ui_app_main(argc, argv, &cb, &ad);
}
