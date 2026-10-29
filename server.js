const http = require('http');
const fs = require('fs');
const crypto = require('crypto');

const sessions = new Map();

const rpc = async (session, path, params) => {
  const response = await fetch(session.url + path, {
    method: 'POST',
    headers: {
      'content-type': 'application/json',
      ...(session.cookie ? { cookie: session.cookie } : {})
    },
    body: JSON.stringify({
      jsonrpc: '2.0',
      method: 'call',
      params,
      id: Date.now()
    }),
    redirect: 'manual'
  });

  const cookie = response.headers.get('set-cookie');

  if (cookie) {
    session.cookie = cookie.split(';')[0];
  }

  const result = await response.json();

  if (result.error) {
    throw new Error(
      result.error.data?.message || result.error.message
    );
  }

  return result.result;
};

http.createServer(async (req, res) => {
  try {
    if (req.method === 'GET' && req.url === '/') {
      res.writeHead(200, {
        'content-type': 'text/html; charset=utf-8'
      });

      return res.end(
        fs.readFileSync(__dirname + '/index.html')
      );
    }

    if (req.method !== 'POST') {
      throw new Error('Not found');
    }

    const chunks = [];

    for await (const chunk of req) {
      chunks.push(chunk);
    }

    const body = JSON.parse(
      Buffer.concat(chunks).toString() || '{}'
    );

    let id = (req.headers.cookie || '')
      .match(/(?:^|; )odohelper=([^;]+)/)?.[1];

    let session = sessions.get(id);
    let output;

    if (req.url === '/login') {
      const url = new URL(body.url);

      if (!['http:', 'https:'].includes(url.protocol)) {
        throw new Error('رابط غير صالح');
      }

      session = { url: url.origin };
      let db = body.db;

      if (!db) {
        const databases = await rpc(
          session,
          '/web/database/list',
          {}
        );

        if (databases.length !== 1) {
          throw new Error(
            'اكتب اسم قاعدة البيانات للمتابعة'
          );
        }

        db = databases[0];
      }

      const auth = await rpc(
        session,
        '/web/session/authenticate',
        {
          db,
          login: body.login,
          password: body.password
        }
      );

      if (!auth.uid) {
        throw new Error('بيانات الدخول غير صحيحة');
      }

      id = crypto.randomBytes(32).toString('hex');
      sessions.set(id, session);

      res.setHeader(
        'Set-Cookie',
        `odohelper=${id}; HttpOnly; SameSite=Strict; Path=/`
      );

      output = { name: auth.name };
    } else {
      if (!session) {
        throw new Error('سجّل الدخول أولاً');
      }

      const call = (model, method, args, kwargs = {}) =>
        rpc(session, '/web/dataset/call_kw', {
          model,
          method,
          args,
          kwargs
        });

      if (req.url === '/contacts') {
        output = [];

        for (const contact of body.contacts || []) {
          if (!contact.name) continue;

          try {
            const existing = contact.phone
              ? await call(
                  'res.partner',
                  'search_read',
                  [[['phone', '=', contact.phone]]],
                  {
                    fields: ['name', 'phone'],
                    limit: 1
                  }
                )
              : [];

            if (existing.length) {
              output.push({
                name: contact.name,
                status: 'موجود بالفعل'
              });
              continue;
            }

            await call('res.partner', 'create', [{
              name: contact.name,
              phone: contact.phone || false,
              email: contact.email || false
            }]);

            output.push({
              name: contact.name,
              status: 'تم الرفع'
            });
          } catch (error) {
            output.push({
              name: contact.name,
              status: error.message
            });
          }
        }
      } else if (req.url === '/reports') {
        const models = [
          ['sale.order', 'مبيعات'],
          ['purchase.order', 'مشتريات'],
          ['account.move', 'حسابات']
        ];

        output = [];

        for (const [model, label] of models) {
          try {
            const rows = await call(
              model,
              'search_read',
              [[['name', 'ilike', body.query || '']]],
              {
                fields: ['name', 'id'],
                limit: 20,
                order: 'id desc'
              }
            );

            output.push(
              ...rows.map(row => ({
                name: row.name,
                label,
                url:
                  session.url +
                  '/web#id=' + row.id +
                  '&model=' + model +
                  '&view_type=form'
              }))
            );
          } catch (error) {
            // قد لا يكون لدى المستخدم صلاحية لهذا القسم.
          }
        }
      } else if (req.url === '/logout') {
        sessions.delete(id);
        output = {};
      } else {
        throw new Error('Not found');
      }
    }

    res.writeHead(200, {
      'content-type': 'application/json'
    });

    res.end(JSON.stringify(output));
  } catch (error) {
    res.writeHead(400, {
      'content-type': 'application/json'
    });

    res.end(JSON.stringify({
      error: error.message
    }));
  }
}).listen(
  process.env.PORT || 3000,
  () => console.log(
    'odohelper running on http://localhost:' +
    (process.env.PORT || 3000)
  )
);