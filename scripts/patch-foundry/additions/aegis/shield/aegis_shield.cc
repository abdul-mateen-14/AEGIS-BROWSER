// Copyright 2026 The Aegis Browser Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#include "services/network/aegis_shield.h"

#include <algorithm>
#include <cstring>

#include "services/network/aegis_shield_blocklist.h"

namespace aegis {
namespace {

// Binary search in a sorted, NUL-terminated domain array.
const char* const* FindExact(const char* const* list,
                             size_t size,
                             const std::string& host) {
  const char* const* end = list + size;
  const char* const* it =
      std::lower_bound(list, end, host,
                       [](const char* entry, const std::string& value) {
                         return std::strcmp(entry, value.c_str()) < 0;
                       });
  if (it != end && host == *it) {
    return it;
  }
  return nullptr;
}

// True when |host| equals a list entry or is a subdomain of one, i.e.
// when any dot-suffix of |host| is in the list.
bool HostInList(const std::string& host, const char* const* list, size_t size) {
  std::string candidate = host;
  while (!candidate.empty()) {
    if (FindExact(list, size, candidate) != nullptr) {
      return true;
    }
    size_t dot = candidate.find('.');
    if (dot == std::string::npos) {
      break;
    }
    candidate = candidate.substr(dot + 1);
  }
  return false;
}

}  // namespace

bool AegisShield::ShouldBlock(const GURL& url,
                              const std::optional<url::Origin>& initiator) {
  if (!url.SchemeIsHTTPOrHTTPS()) {
    return false;
  }
  const std::string host = url.host();
  if (host.empty()) {
    return false;
  }
  // 1) The Aegis ecosystem / sync backend are never blocked.
  if (HostInList(host, kAegisAllowDomains, kAegisAllowDomainsCount)) {
    return false;
  }
  // 2) Browser-initiated requests (omnibox, sync, component updates) pass.
  if (!initiator.has_value()) {
    return false;
  }
  // 3) Same-party requests pass: first-party browsing cannot break.
  if (initiator->GetURL().host() == host) {
    return false;
  }
  // 4) Third-party request to a known ad/tracker host: blocked.
  return HostInList(host, kAegisBlockDomains, kAegisBlockDomainsCount);
}

}  // namespace aegis
