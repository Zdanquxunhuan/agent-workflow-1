-- Dedicated local integration ledger. All seeded data is synthetic.
create table orders (
    id varchar(64) primary key,
    customer_id varchar(64) not null,
    customer_name varchar(128) not null,
    status varchar(32) not null,
    currency char(3) not null default 'CNY',
    total_amount decimal(12,2) not null,
    paid_amount decimal(12,2) not null,
    refunded_amount decimal(12,2) not null default 0,
    shipping_address varchar(500) not null,
    refund_behavior varchar(32) not null default 'NORMAL',
    synthetic boolean not null default true,
    updated_at datetime(6) not null,
    constraint chk_amount check (refunded_amount >= 0 and refunded_amount <= paid_amount)
) engine=InnoDB default charset=utf8mb4;
create table order_items (
    order_id varchar(64) not null,
    sku varchar(64) not null,
    title varchar(128) not null,
    quantity int not null,
    unit_price decimal(12,2) not null,
    primary key(order_id,sku),
    foreign key(order_id) references orders(id)
) engine=InnoDB default charset=utf8mb4;
create table payments (
    id varchar(64) primary key,
    order_id varchar(64) not null unique,
    status varchar(32) not null,
    amount decimal(12,2) not null,
    refunded_amount decimal(12,2) not null default 0,
    gateway_txn_id varchar(128) not null,
    paid_at datetime(6) not null,
    foreign key(order_id) references orders(id)
) engine=InnoDB default charset=utf8mb4;
create table refunds (
    id varchar(64) primary key,
    idempotency_key varchar(160) not null unique,
    order_id varchar(64) not null,
    amount decimal(12,2) not null,
    status varchar(32) not null,
    behavior varchar(32) not null,
    settle_after datetime(6) not null,
    created_at datetime(6) not null,
    completed_at datetime(6),
    foreign key(order_id) references orders(id),
    constraint chk_refund_amount check(amount > 0)
) engine=InnoDB default charset=utf8mb4;
create index idx_refunds_due on refunds(status,settle_after);
create index idx_refunds_order on refunds(order_id,status);
create table operation_audit (
    id bigint primary key auto_increment,
    refund_id varchar(64) not null,
    action varchar(64) not null,
    reason varchar(500) not null,
    occurred_at datetime(6) not null
) engine=InnoDB default charset=utf8mb4;
