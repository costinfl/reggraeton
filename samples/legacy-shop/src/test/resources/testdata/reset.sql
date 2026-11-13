-- Restores the reference data the cart scenarios expect (run by hand before a test run)
DELETE FROM cart_items;
INSERT INTO products (id, name, price) VALUES (product_seq.NEXTVAL, 'Coffee mug', 9.50);
BEGIN
  shop_admin.reset_stock('MUG', 100);
END;
/
SELECT shop_admin.next_invoice_no() FROM dual;
COMMIT;
