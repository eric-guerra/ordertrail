'use strict';

/**
 * Redux module for the order tracking page: state, reducer, selectors and async actions.
 * The network layer is injected so the whole flow runs under Node in tests.
 */
var T = {
  LOOKUP_STARTED: 'track/lookupStarted',
  LOOKUP_SUCCEEDED: 'track/lookupSucceeded',
  LOOKUP_FAILED: 'track/lookupFailed',
  LIVE_UPDATE: 'track/liveUpdate',
  LIVE_STATUS: 'track/liveStatus',
  RESET: 'track/reset'
};

var initial = { query: null, loading: false, error: null, order: null, live: 'off' };

function reducer(state, action) {
  state = state || initial;
  switch (action.type) {
    case T.LOOKUP_STARTED: return Object.assign({}, initial, { query: action.query, loading: true });
    case T.LOOKUP_SUCCEEDED: return Object.assign({}, state, { loading: false, order: action.order });
    case T.LOOKUP_FAILED: return Object.assign({}, state, { loading: false, error: action.error });
    case T.LIVE_UPDATE:
      // Ignore updates for a different order or older snapshots that arrive late.
      if (!state.order || action.order.orderNo !== state.order.orderNo) return state;
      if (action.order.updatedAt < state.order.updatedAt) return state;
      return Object.assign({}, state, { order: action.order });
    case T.LIVE_STATUS: return Object.assign({}, state, { live: action.status });
    case T.RESET: return initial;
    default: return state;
  }
}

var STATUS_COPY = {
  PLACED: { title: 'Order received', tone: 'info' },
  PROCESSING: { title: "We're getting your order ready", tone: 'info' },
  PARTIALLY_SHIPPED: { title: 'Part of your order has shipped', tone: 'info' },
  SHIPPED: { title: 'On its way', tone: 'info' },
  PARTIALLY_DELIVERED: { title: 'Some items have arrived', tone: 'success' },
  DELIVERED: { title: 'Delivered', tone: 'success' },
  READY_FOR_PICKUP: { title: 'Ready for pickup', tone: 'success' },
  COMPLETED: { title: 'All done', tone: 'success' },
  PARTIALLY_RETURNED: { title: 'Return received for some items', tone: 'neutral' },
  RETURNED: { title: 'Returned', tone: 'neutral' },
  CANCELLED: { title: 'Cancelled', tone: 'neutral' },
  ATTENTION: { title: 'There is a problem with a delivery', tone: 'warning' }
};

var STEPS = ['PLACED', 'PROCESSING', 'SHIPPED', 'DELIVERED'];

/** Progress tracker position for the headline status (null hides the tracker). */
function progressStep(status) {
  switch (status) {
    case 'PLACED': return 0;
    case 'PROCESSING': return 1;
    case 'PARTIALLY_SHIPPED': case 'SHIPPED': case 'ATTENTION': case 'READY_FOR_PICKUP': return 2;
    case 'PARTIALLY_DELIVERED': return 2;
    case 'DELIVERED': case 'COMPLETED': return 3;
    default: return null;
  }
}

/** Groups lines into shipments (same tracking number), pickups and unfulfilled lines for display. */
function groups(order) {
  if (!order) return [];
  var byKey = {};
  var out = [];
  order.lines.forEach(function (l) {
    var key = l.trackingNumber ? 'track:' + l.trackingNumber : l.fulfillment === 'PICKUP' ? 'pickup:' + l.storeId : 'pending';
    if (!byKey[key]) {
      byKey[key] = { key: key, carrier: l.carrier || null, trackingNumber: l.trackingNumber || null, storeId: l.fulfillment === 'PICKUP' ? l.storeId : null,
        estimatedDelivery: l.estimatedDelivery || null, lines: [] };
      out.push(byKey[key]);
    }
    byKey[key].lines.push(l);
  });
  return out;
}

function headline(order) {
  return order ? STATUS_COPY[order.status] || { title: order.status, tone: 'info' } : null;
}

function validateQuery(q) {
  var errors = {};
  if (!/^[A-Za-z]\d{3,10}$/.test((q.orderNo || '').trim())) errors.orderNo = 'Order numbers look like W1234567';
  if (!/^[^@\s]+@[^@\s]+\.[^@\s]+$/.test((q.email || '').trim())) errors.email = 'Enter the email you used at checkout';
  return errors;
}

/** Thunk: look up an order, then subscribe to live updates. */
function lookup(query, api) {
  return function (dispatch) {
    dispatch({ type: T.LOOKUP_STARTED, query: query });
    var errors = validateQuery(query);
    if (Object.keys(errors).length) {
      dispatch({ type: T.LOOKUP_FAILED, error: errors.orderNo || errors.email });
      return Promise.resolve(null);
    }
    return api.track(query.orderNo.trim(), query.email.trim()).then(function (order) {
      dispatch({ type: T.LOOKUP_SUCCEEDED, order: order });
      if (api.subscribe) {
        dispatch({ type: T.LIVE_STATUS, status: 'connecting' });
        return api.subscribe(order.orderNo, query.email.trim(), {
          onOpen: function () { dispatch({ type: T.LIVE_STATUS, status: 'live' }); },
          onUpdate: function (o) { dispatch({ type: T.LIVE_UPDATE, order: o }); },
          onError: function () { dispatch({ type: T.LIVE_STATUS, status: 'reconnecting' }); }
        });
      }
      return null;
    }, function (err) {
      dispatch({ type: T.LOOKUP_FAILED, error: err.message || 'Something went wrong' });
      return null;
    });
  };
}

/** Browser API (fetch + EventSource). */
function browserApi(base) {
  base = base || '';
  return {
    track: function (orderNo, email) {
      return fetch(base + '/api/track/' + encodeURIComponent(orderNo) + '?email=' + encodeURIComponent(email)).then(function (res) {
        return res.json().then(function (body) {
          if (!res.ok) throw new Error(body.error || 'Lookup failed');
          return body;
        });
      });
    },
    subscribe: function (orderNo, email, handlers) {
      var es = new EventSource(base + '/api/track/' + encodeURIComponent(orderNo) + '/stream?email=' + encodeURIComponent(email));
      es.onopen = handlers.onOpen;
      es.onerror = handlers.onError;
      es.addEventListener('order', function (e) { handlers.onUpdate(JSON.parse(e.data)); });
      return function () { es.close(); };
    }
  };
}

module.exports = { T: T, reducer: reducer, lookup: lookup, groups: groups, headline: headline, progressStep: progressStep,
  validateQuery: validateQuery, browserApi: browserApi, STEPS: STEPS };
