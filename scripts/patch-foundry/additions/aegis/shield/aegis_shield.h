// Copyright 2026 The Aegis Browser Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#ifndef SERVICES_NETWORK_AEGIS_SHIELD_H_
#define SERVICES_NETWORK_AEGIS_SHIELD_H_

#include <optional>
#include <string>

#include "url/gurl.h"
#include "url/origin.h"

namespace aegis {

// Aegis Shield: a tiny, zero-dependency, compile-time network-level
// blocklist engine. Requests to known ad/tracker hosts are dropped with
// net::ERR_BLOCKED_BY_CLIENT (the same error an adblock extension
// produces), Brave-shields style, with three safety valves so first-party
// browsing can never break:
//
//   1. Browser-initiated requests (no initiator: omnibox navigation,
//      Aegis sync, component updates, Safe Browsing) always pass.
//   2. Same-party requests (initiator host == target host) always pass.
//   3. Hosts on the built-in allowlist (the Aegis ecosystem domains and
//      the Aegis sync backend) always pass.
//
// Data: sorted, wildcard-free domain lists generated from EasyList and
// EasyPrivacy network rules by aegis/shield/gen_blocklist.py in the Aegis
// repository (see aegis_shield_blocklist.cc). Host matching is exact or
// subdomain ("doubleclick.net" blocks "ads.doubleclick.net").
//
// Deliberately out of scope for v1: cosmetic/element filtering, per-site
// shield toggles, custom user lists, and non-HTTP(S) schemes.
class AegisShield {
 public:
  AegisShield() = delete;
  ~AegisShield() = delete;

  // Returns true when the request should be blocked.
  static bool ShouldBlock(const GURL& url,
                          const std::optional<url::Origin>& initiator);
};

}  // namespace aegis

#endif  // SERVICES_NETWORK_AEGIS_SHIELD_H_
