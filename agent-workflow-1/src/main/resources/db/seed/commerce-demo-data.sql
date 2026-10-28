-- Demo-only data. Loaded by CommerceDemoDataSeeder when
-- COMMERCE_SEED_DEMO_DATA=true. Never enable this on a production database.

insert ignore into customers (id, name, phone_masked, email_masked, created_at) values
    ('CUST-001', '张伟', '138****0001', 'zhang***@example.com', '2026-01-05 09:00:00'),
    ('CUST-002', '李娜', '139****0002', 'li***@example.com', '2026-01-12 10:30:00'),
    ('CUST-003', '王强', '137****0003', 'wang***@example.com', '2026-02-03 14:10:00'),
    ('CUST-004', '赵敏', '136****0004', 'zhao***@example.com', '2026-02-18 16:45:00');

insert ignore into orders (id, customer_id, status, currency, total_amount, paid_amount, refunded_amount, shipping_address, version, created_at, updated_at) values
    ('ORD-1001', 'CUST-001', 'SHIPPED', 'CNY', 199.00, 199.00, 0.00, '上海市浦东新区世纪大道 100 号 8 栋 1201', 1, '2026-09-18 10:15:00', '2026-09-20 09:30:00'),
    ('ORD-1002', 'CUST-002', 'DELIVERED', 'CNY', 59.00, 59.00, 0.00, '杭州市西湖区文三路 88 号 3 单元 502', 2, '2026-09-10 11:20:00', '2026-09-14 18:05:00'),
    ('ORD-1003', 'CUST-001', 'PAID', 'CNY', 328.00, 328.00, 0.00, '上海市徐汇区漕溪北路 18 号 6 楼', 1, '2026-10-21 09:05:00', '2026-10-21 09:06:00'),
    ('ORD-1004', 'CUST-003', 'ALLOCATED', 'CNY', 89.90, 89.90, 0.00, '北京市朝阳区建国路 91 号 2 号楼 1703', 1, '2026-10-22 13:40:00', '2026-10-23 08:20:00'),
    ('ORD-1005', 'CUST-004', 'PARTIALLY_REFUNDED', 'CNY', 450.00, 450.00, 50.00, '广州市天河区体育西路 103 号 2201', 3, '2026-08-28 15:00:00', '2026-09-02 10:10:00'),
    ('ORD-1006', 'CUST-002', 'CANCELLED', 'CNY', 120.00, 0.00, 0.00, '杭州市滨江区江南大道 588 号 9 楼', 2, '2026-10-01 09:00:00', '2026-10-01 09:30:00'),
    ('ORD-1007', 'CUST-003', 'REFUNDED', 'CNY', 35.00, 35.00, 35.00, '北京市海淀区中关村大街 27 号 1105', 3, '2026-07-15 12:00:00', '2026-07-20 16:40:00'),
    ('ORD-1008', 'CUST-004', 'PAID', 'CNY', 1299.00, 1299.00, 0.00, '深圳市南山区科技园科苑路 15 号 5 栋', 1, '2026-10-24 20:00:00', '2026-10-24 20:02:00'),
    ('ORD-1009', 'CUST-001', 'CREATED', 'CNY', 66.00, 0.00, 0.00, '上海市静安区南京西路 1266 号 2802', 0, '2026-10-26 17:55:00', '2026-10-26 17:55:00'),
    ('ORD-1010', 'CUST-002', 'SHIPPED', 'CNY', 240.00, 240.00, 0.00, '杭州市余杭区文一西路 969 号 4 号楼', 2, '2026-10-19 08:30:00', '2026-10-20 14:15:00');

insert ignore into order_items (id, order_id, sku, title, quantity, unit_price) values
    ('ITM-1001', 'ORD-1001', 'SKU-A-001', '无线降噪耳机', 1, 199.00),
    ('ITM-1002', 'ORD-1002', 'SKU-A-002', '快充数据线 2m', 1, 59.00),
    ('ITM-1003', 'ORD-1003', 'SKU-B-001', '机械键盘 87 键', 1, 199.00),
    ('ITM-1004', 'ORD-1003', 'SKU-B-002', '人体工学腕托', 1, 129.00),
    ('ITM-1005', 'ORD-1004', 'SKU-C-001', '桌面收纳盒', 1, 89.90),
    ('ITM-1006', 'ORD-1005', 'SKU-D-001', '智能手表表带', 2, 150.00),
    ('ITM-1007', 'ORD-1005', 'SKU-D-002', '屏幕保护膜', 1, 150.00),
    ('ITM-1008', 'ORD-1006', 'SKU-E-001', '旅行背包 25L', 1, 120.00),
    ('ITM-1009', 'ORD-1007', 'SKU-F-001', 'USB-C 扩展坞', 1, 35.00),
    ('ITM-1010', 'ORD-1008', 'SKU-G-001', '轻薄笔记本 14 英寸', 1, 1299.00),
    ('ITM-1011', 'ORD-1009', 'SKU-H-001', '保温杯 500ml', 1, 66.00),
    ('ITM-1012', 'ORD-1010', 'SKU-I-001', '空气净化器滤芯', 2, 120.00);

insert ignore into payments (id, order_id, channel, status, amount, refunded_amount, gateway_txn_id, paid_at, created_at, updated_at) values
    ('PAY-1001', 'ORD-1001', 'ALIPAY', 'SUCCEEDED', 199.00, 0.00, 'ALI-SEED-1001', '2026-09-18 10:16:00', '2026-09-18 10:16:00', '2026-09-18 10:16:00'),
    ('PAY-1002', 'ORD-1002', 'WECHAT', 'SUCCEEDED', 59.00, 0.00, 'WX-SEED-1002', '2026-09-10 11:21:00', '2026-09-10 11:21:00', '2026-09-10 11:21:00'),
    ('PAY-1003', 'ORD-1003', 'ALIPAY', 'SUCCEEDED', 328.00, 0.00, 'ALI-SEED-1003', '2026-10-21 09:06:00', '2026-10-21 09:06:00', '2026-10-21 09:06:00'),
    ('PAY-1004', 'ORD-1004', 'WECHAT', 'SUCCEEDED', 89.90, 0.00, 'WX-SEED-1004', '2026-10-22 13:41:00', '2026-10-22 13:41:00', '2026-10-22 13:41:00'),
    ('PAY-1005', 'ORD-1005', 'CARD', 'PARTIALLY_REFUNDED', 450.00, 50.00, 'CARD-SEED-1005', '2026-08-28 15:01:00', '2026-08-28 15:01:00', '2026-09-02 10:10:00'),
    ('PAY-1007', 'ORD-1007', 'WECHAT', 'REFUNDED', 35.00, 35.00, 'WX-SEED-1007', '2026-07-15 12:01:00', '2026-07-15 12:01:00', '2026-07-20 16:40:00'),
    ('PAY-1008', 'ORD-1008', 'ALIPAY', 'SUCCEEDED', 1299.00, 0.00, 'ALI-SEED-1008', '2026-10-24 20:02:00', '2026-10-24 20:02:00', '2026-10-24 20:02:00'),
    ('PAY-1010', 'ORD-1010', 'ALIPAY', 'SUCCEEDED', 240.00, 0.00, 'ALI-SEED-1010', '2026-10-19 08:31:00', '2026-10-19 08:31:00', '2026-10-19 08:31:00');

insert ignore into refunds (id, order_id, payment_id, amount, status, reason, idempotency_key, gateway_refund_id, requested_by, task_id, requested_at, completed_at) values
    ('REF-5001', 'ORD-1005', 'PAY-1005', 50.00, 'SUCCEEDED', '表带尺寸不合适，部分退款', 'seed-refund-5001', 'SANDBOX-REF-5001', 'seed-supervisor', null, '2026-09-02 09:50:00', '2026-09-02 10:10:00'),
    ('REF-5002', 'ORD-1007', 'PAY-1007', 35.00, 'SUCCEEDED', '商品未使用，整单退款', 'seed-refund-5002', 'SANDBOX-REF-5002', 'seed-supervisor', null, '2026-07-20 16:20:00', '2026-07-20 16:40:00');

insert ignore into shipments (id, order_id, carrier, tracking_no, status, idempotency_key, created_at, updated_at, shipped_at, delivered_at) values
    ('SHP-3001', 'ORD-1001', 'SF Express', 'SF-SEED-1001', 'IN_TRANSIT', 'seed-shipment-3001', '2026-09-19 08:00:00', '2026-09-20 09:30:00', '2026-09-19 10:00:00', null),
    ('SHP-3002', 'ORD-1002', 'YTO Express', 'YTO-SEED-1002', 'DELIVERED', 'seed-shipment-3002', '2026-09-11 09:00:00', '2026-09-14 18:05:00', '2026-09-11 14:00:00', '2026-09-14 18:05:00'),
    ('SHP-3010', 'ORD-1010', 'JD Logistics', 'JD-SEED-1010', 'IN_TRANSIT', 'seed-shipment-3010', '2026-10-20 09:00:00', '2026-10-20 14:15:00', '2026-10-20 14:15:00', null);

insert ignore into shipment_events (shipment_id, status, location, description, occurred_at) values
    ('SHP-3001', 'CREATED', '上海仓', '仓库已出库', '2026-09-19 09:30:00'),
    ('SHP-3001', 'IN_TRANSIT', '上海转运中心', '包裹已发往杭州转运中心', '2026-09-20 09:30:00'),
    ('SHP-3002', 'CREATED', '杭州仓', '仓库已出库', '2026-09-11 13:50:00'),
    ('SHP-3002', 'IN_TRANSIT', '杭州转运中心', '包裹运输中', '2026-09-12 08:20:00'),
    ('SHP-3002', 'DELIVERED', '杭州西湖区', '包裹已签收', '2026-09-14 18:05:00'),
    ('SHP-3010', 'CREATED', '杭州仓', '仓库已出库', '2026-10-20 13:50:00'),
    ('SHP-3010', 'IN_TRANSIT', '杭州转运中心', '包裹运输中', '2026-10-20 14:15:00');

insert ignore into tickets (id, order_id, customer_id, status, priority, subject, created_at, updated_at) values
    ('TKT-2001', 'ORD-1001', 'CUST-001', 'OPEN', 'NORMAL', '物流超过 48 小时未更新', '2026-09-22 10:00:00', '2026-09-22 10:00:00'),
    ('TKT-2002', 'ORD-1002', 'CUST-002', 'CLOSED', 'HIGH', '退款到账时间咨询', '2026-09-15 09:30:00', '2026-09-16 11:00:00');

insert ignore into integration_commands (id, channel, command_type, aggregate_id, payload_json, status, attempts, idempotency_key, external_ref, last_error, next_attempt_at, locked_by, locked_until, created_at, updated_at) values
    ('CMD-SEED-5001', 'PAYMENT', 'REFUND', 'REF-5001', '{"refundId":"REF-5001","orderId":"ORD-1005","amount":50.00}', 'ACKNOWLEDGED', 1, 'seed-refund-5001:cmd', 'SANDBOX-REF-5001', null, null, null, null, '2026-09-02 09:50:00', '2026-09-02 10:10:00'),
    ('CMD-SEED-5002', 'PAYMENT', 'REFUND', 'REF-5002', '{"refundId":"REF-5002","orderId":"ORD-1007","amount":35.00}', 'ACKNOWLEDGED', 1, 'seed-refund-5002:cmd', 'SANDBOX-REF-5002', null, null, null, null, '2026-07-20 16:20:00', '2026-07-20 16:40:00');
