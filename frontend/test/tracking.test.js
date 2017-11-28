'use strict';

var test = require('node:test');
var assert = require('node:assert/strict');
var redux = require('redux');
var tracking = require('../src/tracking');

var thunk = function (store) { return function (next) { return function (action) { return typeof action === 'function' ? action(store.dispatch, store.getState) : next(action); }; }; };

var ORDER = {
  orderNo: 'W1001', status: 'PARTIALLY_SHIPPED', totalCents: 45398, updatedAt: '2017-06-01T16:00:00Z',
  lines: [
    { lineId: 'L1', name: 'Tent', qty: 1, status: 'SHIPPED', fulfillment: 'SHIP', carrier: 'UPS', trackingNumber: '1Z1' },
    { lineId: 'L2', name: 'Stove', qty: 1, status: 'SHIPPED', fulfillment: 'SHIP', carrier: 'UPS', trackingNumber: '1Z1' },
    { lineId: 'L3', name: 'Lamp', qty: 1, status: 'READY_FOR_PICKUP', fulfillment: 'PICKUP', storeId: 'HOU01' },
    { lineId: 'L4', name: 'Bottle', qty: 2, status: 'ORDERED', fulfillment: 'SHIP' }
  ],
  timeline: []
};

function setup(api) {
  return redux.createStore(tracking.reducer, redux.applyMiddleware(thunk));
}

test('lookup validates before calling the API', async function () {
  var calls = 0;
  var store = setup();
  await store.dispatch(tracking.lookup({ orderNo: '12', email: 'x' }, { track: function () { calls++; } }));
  assert.equal(calls, 0);
  assert.match(store.getState().error, /W1234567/);
});

test('successful lookup subscribes to live updates and applies only newer snapshots', async function () {
  var handlers;
  var api = {
    track: function () { return Promise.resolve(ORDER); },
    subscribe: function (no, email, h) { handlers = h; return function () {}; }
  };
  var store = setup();
  await store.dispatch(tracking.lookup({ orderNo: ' W1001 ', email: 'ana@example.com ' }, api));
  assert.equal(store.getState().order.orderNo, 'W1001');
  assert.equal(store.getState().live, 'connecting');
  handlers.onOpen();
  assert.equal(store.getState().live, 'live');

  handlers.onUpdate(Object.assign({}, ORDER, { status: 'DELIVERED', updatedAt: '2017-06-03T10:00:00Z' }));
  assert.equal(store.getState().order.status, 'DELIVERED');
  handlers.onUpdate(Object.assign({}, ORDER, { status: 'SHIPPED', updatedAt: '2017-06-02T10:00:00Z' }));
  assert.equal(store.getState().order.status, 'DELIVERED', 'late, older snapshot ignored');
  handlers.onUpdate(Object.assign({}, ORDER, { orderNo: 'OTHER', status: 'CANCELLED', updatedAt: '2018-01-01T00:00:00Z' }));
  assert.equal(store.getState().order.orderNo, 'W1001', 'updates for other orders ignored');
});

test('lookup failures surface the server message', async function () {
  var store = setup();
  await store.dispatch(tracking.lookup({ orderNo: 'W1001', email: 'a@b.co' }, { track: function () { return Promise.reject(new Error("We couldn't find an order")); } }));
  assert.equal(store.getState().error, "We couldn't find an order");
  assert.equal(store.getState().loading, false);
});

test('lines are grouped into shipments, pickups and pending', function () {
  var g = tracking.groups(ORDER);
  assert.deepEqual(g.map(function (x) { return x.key; }), ['track:1Z1', 'pickup:HOU01', 'pending']);
  assert.equal(g[0].lines.length, 2);
  assert.equal(g[1].storeId, 'HOU01');
});

test('headline copy and progress positions', function () {
  assert.equal(tracking.headline(ORDER).title, 'Part of your order has shipped');
  assert.equal(tracking.headline({ status: 'ATTENTION' }).tone, 'warning');
  assert.equal(tracking.progressStep('PLACED'), 0);
  assert.equal(tracking.progressStep('DELIVERED'), 3);
  assert.equal(tracking.progressStep('CANCELLED'), null, 'no tracker for cancelled orders');
});
