#ifndef EP_WARNING_UI_H
#define EP_WARNING_UI_H

#include <Elementary.h>

typedef struct {
    Evas_Object *win;
    Evas_Object *conform;
    Evas_Object *naviframe;
    Evas_Object *status_label;
    Evas_Object *toggle_button;
    Evas_Object *sensitivity_slider;
    Evas_Object *sensitivity_label;
    bool monitoring;
} appdata_t;

void ui_create(appdata_t *ad);
void ui_refresh_status(appdata_t *ad);

#endif
