import React, { useEffect, useRef, useState } from 'react';
import ReactDOM from 'react-dom';
import { applyMiddleware, createStore } from 'redux';
import { Provider, useDispatch, useSelector } from 'react-redux';
import tracking from './tracking';

const thunk = (store) => (next) => (action) => (typeof action === 'function' ? action(store.dispatch, store.getState) : next(action));
const store = createStore(tracking.reducer, applyMiddleware(thunk));
const api = tracking.browserApi('');

const money = (c) => `$${(c / 100).toFixed(2)}`;
const date = (iso) => (iso ? new Date(iso).toLocaleString(undefined, { month: 'short', day: 'numeric', hour: 'numeric', minute: '2-digit' }) : '');
const STATUS_LABEL = { ORDERED: 'Preparing', SHIPPED: 'Shipped', OUT_FOR_DELIVERY: 'Out for delivery', DELIVERED: 'Delivered', READY_FOR_PICKUP: 'Ready for pickup',
  PICKED_UP: 'Picked up', RETURNED: 'Returned', CANCELLED: 'Cancelled', DELIVERY_EXCEPTION: 'Delivery problem' };

function LookupForm() {
  const dispatch = useDispatch();
  const unsubscribe = useRef(null);
  const params = new URLSearchParams(location.search);
  const [orderNo, setOrderNo] = useState(params.get('order') || '');
  const [email, setEmail] = useState('');
  const submit = async (e) => {
    e.preventDefault();
    if (unsubscribe.current) unsubscribe.current();
    unsubscribe.current = await dispatch(tracking.lookup({ orderNo, email }, api));
  };
  useEffect(() => () => unsubscribe.current && unsubscribe.current(), []);
  return (
    <form className="lookup" onSubmit={submit}>
      <input placeholder="Order number" value={orderNo} onChange={(e) => setOrderNo(e.target.value)} />
      <input placeholder="Email" type="email" value={email} onChange={(e) => setEmail(e.target.value)} />
      <button>Track</button>
    </form>
  );
}

function Progress({ status }) {
  const step = tracking.progressStep(status);
  if (step === null) return null;
  return (
    <ol className="progress">
      {['Placed', 'Preparing', 'Shipped', 'Delivered'].map((label, i) => <li key={label} className={i <= step ? 'done' : ''}>{label}</li>)}
    </ol>
  );
}

function Order() {
  const { order, loading, error, live } = useSelector((s) => s);
  if (loading) return <p className="muted">Looking up your order…</p>;
  if (error) return <p className="error">{error}</p>;
  if (!order) return null;
  const head = tracking.headline(order);
  return (
    <section className="order">
      <header className={`headline ${head.tone}`}>
        <h2>{head.title}</h2>
        <span className="muted">Order {order.orderNo} · {money(order.totalCents)} · {live === 'live' ? '● live' : live}</span>
      </header>
      <Progress status={order.status} />
      {tracking.groups(order).map((g) => (
        <div key={g.key} className="group">
          <h3>
            {g.trackingNumber ? `${g.carrier} ${g.trackingNumber}` : g.storeId ? `Pickup at store ${g.storeId}` : 'Not shipped yet'}
            {g.estimatedDelivery && <span className="muted"> · arriving by {date(g.estimatedDelivery)}</span>}
          </h3>
          <ul>
            {g.lines.map((l) => (
              <li key={l.lineId}>
                <span>{l.qty} × {l.name}</span>
                <span className={`chip ${l.status.toLowerCase()}`}>{STATUS_LABEL[l.status] || l.status}</span>
              </li>
            ))}
          </ul>
        </div>
      ))}
      <h3>History</h3>
      <ul className="timeline">
        {order.timeline.map((t, i) => <li key={i}><time>{date(t.at)}</time> {t.message}</li>)}
      </ul>
    </section>
  );
}

function App() {
  return (
    <div className="page">
      <h1>Track your order</h1>
      <LookupForm />
      <Order />
    </div>
  );
}

ReactDOM.render(<Provider store={store}><App /></Provider>, document.getElementById('root'));
