-- Business system of record for the after-sales agent. The agent reads orders,
-- payments, shipments and tickets here, and writes refunds, address changes and
-- shipment requests. External payment/carrier calls are handed off through
-- integration_commands (transactional outbox) instead of being invoked inline.

create table if not exists customers (
    id varchar(64) primary key,
    name varchar(128) not null,
    phone_masked varchar(32),
    email_masked varchar(160),
    created_at datetime(6) not null
) engine = InnoDB default charset = utf8mb4;

create table if not exists orders (
    id varchar(64) primary key,
    customer_id varchar(64) not null,
    status varchar(32) not null,
    currency char(3) not null default 'CNY',
    total_amount decimal(12, 2) not null,
    paid_amount decimal(12, 2) not null default 0,
    refunded_amount decimal(12, 2) not null default 0,
    shipping_address varchar(500) not null,
    version bigint not null default 0,
    created_at datetime(6) not null,
    updated_at datetime(6) not null,
    constraint fk_orders_customer foreign key (customer_id) references customers(id),
    constraint chk_orders_amounts check (paid_amount >= 0 and refunded_amount >= 0 and refunded_amount <= paid_amount)
) engine = InnoDB default charset = utf8mb4;

create index idx_orders_customer on orders(customer_id);

create table if not exists order_items (
    id varchar(64) primary key,
    order_id varchar(64) not null,
    sku varchar(64) not null,
    title varchar(255) not null,
    quantity integer not null,
    unit_price decimal(12, 2) not null,
    constraint fk_order_items_order foreign key (order_id) references orders(id)
) engine = InnoDB default charset = utf8mb4;

create index idx_order_items_order on order_items(order_id);

create table if not exists payments (
    id varchar(64) primary key,
    order_id varchar(64) not null,
    channel varchar(32) not null,
    status varchar(32) not null,
    amount decimal(12, 2) not null,
    refunded_amount decimal(12, 2) not null default 0,
    gateway_txn_id varchar(128),
    paid_at datetime(6),
    created_at datetime(6) not null,
    updated_at datetime(6) not null,
    constraint fk_payments_order foreign key (order_id) references orders(id),
    constraint uq_payments_order unique (order_id),
    constraint chk_payments_amounts check (amount >= 0 and refunded_amount >= 0 and refunded_amount <= amount)
) engine = InnoDB default charset = utf8mb4;

create table if not exists refunds (
    id varchar(64) primary key,
    order_id varchar(64) not null,
    payment_id varchar(64) not null,
    amount decimal(12, 2) not null,
    status varchar(32) not null,
    reason varchar(500),
    idempotency_key varchar(160) not null,
    gateway_refund_id varchar(128),
    requested_by varchar(128) not null,
    task_id varchar(64),
    requested_at datetime(6) not null,
    completed_at datetime(6),
    constraint fk_refunds_order foreign key (order_id) references orders(id),
    constraint fk_refunds_payment foreign key (payment_id) references payments(id),
    constraint uq_refunds_idempotency unique (idempotency_key),
    constraint chk_refunds_amount check (amount > 0)
) engine = InnoDB default charset = utf8mb4;

create index idx_refunds_order_status on refunds(order_id, status);

create table if not exists shipments (
    id varchar(64) primary key,
    order_id varchar(64) not null,
    carrier varchar(64) not null,
    tracking_no varchar(64),
    status varchar(32) not null,
    idempotency_key varchar(160) not null,
    created_at datetime(6) not null,
    updated_at datetime(6) not null,
    shipped_at datetime(6),
    delivered_at datetime(6),
    constraint fk_shipments_order foreign key (order_id) references orders(id),
    constraint uq_shipments_order unique (order_id),
    constraint uq_shipments_idempotency unique (idempotency_key)
) engine = InnoDB default charset = utf8mb4;

create table if not exists shipment_events (
    id bigint not null auto_increment primary key,
    shipment_id varchar(64) not null,
    status varchar(32) not null,
    location varchar(128),
    description varchar(255),
    occurred_at datetime(6) not null,
    constraint fk_shipment_events_shipment foreign key (shipment_id) references shipments(id)
) engine = InnoDB default charset = utf8mb4;

create index idx_shipment_events_shipment on shipment_events(shipment_id, occurred_at);

create table if not exists address_change_log (
    id bigint not null auto_increment primary key,
    order_id varchar(64) not null,
    old_address varchar(500) not null,
    new_address varchar(500) not null,
    changed_by varchar(128) not null,
    task_id varchar(64),
    idempotency_key varchar(160) not null,
    changed_at datetime(6) not null,
    constraint fk_address_change_order foreign key (order_id) references orders(id),
    constraint uq_address_change_idempotency unique (idempotency_key)
) engine = InnoDB default charset = utf8mb4;

create index idx_address_change_order on address_change_log(order_id, changed_at);

create table if not exists tickets (
    id varchar(64) primary key,
    order_id varchar(64),
    customer_id varchar(64) not null,
    status varchar(32) not null,
    priority varchar(16) not null,
    subject varchar(255) not null,
    created_at datetime(6) not null,
    updated_at datetime(6) not null,
    constraint fk_tickets_customer foreign key (customer_id) references customers(id)
) engine = InnoDB default charset = utf8mb4;

create table if not exists integration_commands (
    id varchar(64) primary key,
    channel varchar(32) not null,
    command_type varchar(48) not null,
    aggregate_id varchar(64) not null,
    payload_json json not null,
    status varchar(32) not null,
    attempts integer not null default 0,
    idempotency_key varchar(160) not null,
    external_ref varchar(128),
    last_error varchar(500),
    next_attempt_at datetime(6),
    locked_by varchar(128),
    locked_until datetime(6),
    created_at datetime(6) not null,
    updated_at datetime(6) not null,
    constraint uq_integration_commands_idempotency unique (idempotency_key)
) engine = InnoDB default charset = utf8mb4;

create index idx_integration_commands_claim on integration_commands(status, next_attempt_at, locked_until, created_at);
