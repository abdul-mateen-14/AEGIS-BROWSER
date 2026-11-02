// Aegis Browser sync backend - worker entry point and router.

import { Env } from './types';
import { errResponse, json } from './util';
import {
  handleChangePassword,
  handleDeleteAccount,
  handleKdfParams,
  handleLogin,
  handleLogout,
  handleRefresh,
  handleRegister,
  requireAuth,
} from './auth';
import { handleListDevices, handleRevokeDevice } from './devices';
import { handleCursor, handleExport, handlePull, handlePush, prune } from './sync';

function corsHeaders(env: Env): Record<string, string> {
  return {
    'Access-Control-Allow-Origin': env.CORS_ORIGIN || '*',
    'Access-Control-Allow-Methods': 'GET, POST, DELETE, OPTIONS',
    'Access-Control-Allow-Headers': 'Authorization, Content-Type',
    'Access-Control-Max-Age': '86400',
  };
}

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    const cors = corsHeaders(env);
    try {
      if (request.method === 'OPTIONS') {
        return new Response(null, { status: 204, headers: cors });
      }

      const url = new URL(request.url);
      const path = url.pathname.replace(/\/+$/, '') || '/';
      const method = request.method;
      const seg = path.split('/').filter(Boolean); // e.g. ["v1","sync","push"]

      // ---------- public ----------
      if (method === 'GET' && path === '/health') {
        return json({ ok: true, service: 'aegis-sync', version: '1.0.0' });
      }

      if (seg[0] === 'v1' && seg[1] === 'auth') {
        if (method === 'POST' && seg[2] === 'register') return await handleRegister(request, env);
        if (method === 'GET' && seg[2] === 'kdf-params') return await handleKdfParams(request, env, url);
        if (method === 'POST' && seg[2] === 'login') return await handleLogin(request, env);
        if (method === 'POST' && seg[2] === 'refresh') return await handleRefresh(request, env);
      }

      // ---------- authenticated ----------
      if (seg[0] === 'v1') {
        const auth = await requireAuth(request, env);

        if (seg[1] === 'auth') {
          if (method === 'POST' && seg[2] === 'logout') return await handleLogout(request, env, auth);
          if (method === 'POST' && seg[2] === 'change-password') return await handleChangePassword(request, env, auth);
          if (method === 'DELETE' && seg[2] === 'account') return await handleDeleteAccount(request, env, auth);
        }

        if (seg[1] === 'devices') {
          if (method === 'GET' && seg.length === 2) return await handleListDevices(request, env, auth);
          if (method === 'POST' && seg[2] !== undefined && seg[3] === 'revoke') {
            return await handleRevokeDevice(request, env, auth, seg[2]);
          }
        }

        if (seg[1] === 'sync') {
          if (method === 'GET' && seg[2] === 'cursor') return await handleCursor(request, env, auth);
          if (method === 'GET' && seg[2] === 'pull') return await handlePull(request, env, auth, url);
          if (method === 'POST' && seg[2] === 'push') return await handlePush(request, env, auth);
          if (method === 'GET' && seg[2] === 'export') return await handleExport(request, env, auth, url);
        }

        if (method === 'DELETE' && seg[1] === 'account' && seg.length === 2) {
          return await handleDeleteAccount(request, env, auth);
        }
      }

      return json({ error: { code: 'not_found', message: `No route for ${method} ${path}` } }, 404, cors);
    } catch (e) {
      return errResponse(e, cors);
    }
  },

  async scheduled(_event: unknown, env: Env): Promise<void> {
    await prune(env);
  },
};
