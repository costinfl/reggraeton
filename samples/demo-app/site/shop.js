// Shared catalog and cart (kept in localStorage) of the demo shop.
const CATALOG = [
  { name: 'HDMI Cable', price: 14.99 },
  { name: 'Laptop Pro 14', price: 1300.00 },
  { name: 'Laptop Stand', price: 49.99 },
  { name: 'USB-C Cable', price: 9.99 },
  { name: 'USB-C Charger', price: 29.99 }
];

function readCart() {
  try {
    return JSON.parse(localStorage.getItem('cart') || '{}');
  } catch (e) {
    return {};
  }
}

function addToCart(name) {
  const cart = readCart();
  cart[name] = (cart[name] || 0) + 1;
  localStorage.setItem('cart', JSON.stringify(cart));
}

function cartCount() {
  return Object.values(readCart()).reduce((a, b) => a + b, 0);
}

function priceOf(name) {
  const product = CATALOG.find(p => p.name === name);
  return product ? product.price : 0;
}
