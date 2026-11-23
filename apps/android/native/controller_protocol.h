/*
 * @Author: DI JUNKUN
 * @Date: 2026-10-02
 * Copyright (c) 2026 by DI JUNKUN, All Rights Reserved.
 */

#ifndef _CONTROLLER_PROTOCOL_H_
#define _CONTROLLER_PROTOCOL_H_

#include "../../../libs/controller/include/mobile_controller_protocol.h"
#include <preview_subscription.h>
namespace crossdesk::android_controller {
using mobile_controller::SecureAttentionMessage;
using mobile_controller::PrivacyMessage;
using mobile_controller::VideoSettingsMessage;
using mobile_controller::NetworkReport;

// Wall previews share the desktop DeskHub subscription wire format so a
// desktop host treats the phone exactly like another preview consumer.
inline std::string PreviewSubscriptionMessage(int fps, int width, int height,
                                              int quality, int frame_rate,
                                              int preference, uint32_t request_id) {
  if (fps < 0 || fps > 60 || quality < 0 || quality > 2 ||
      (frame_rate != 30 && frame_rate != 60) || preference < 0 ||
      preference > 2 || (width != 0 && (width < 2 || width > 3840)) ||
      (height != 0 && (height < 2 || height > 2160)) ||
      ((width & 1) || (height & 1)) || request_id == 0)
    return {};
  PreviewSubscription s;
  s.request_id = request_id;
  s.fps = fps;
  s.frame_rate = frame_rate;
  // Sparse preview input preserves spatial detail instead of applying the
  // interactive latency budget; the wire value is what the host reads.
  s.preference = fps == 1 || fps == 8 || fps == 15 ? 1 : preference;
  s.audio = false;
  s.quality = quality;
  // Bounded preview frames need the physical-pixel extension, and the tile is
  // the only consumer, so the bitrate hint keeps small tiles cheap.
  s.version = 2;
  s.width = width;
  s.height = height;
  s.max_bitrate_bps = 2500000;
  return s.ToJson().dump();
}
}

#endif
