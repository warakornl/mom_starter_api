# Observability Stack — Dev Guide

คู่มือนี้ครอบคลุม: วิธีรัน stack, การดู metrics บน Grafana, การดู log บน Kibana,
และข้อควรระวังเรื่อง resource + ความปลอดภัย

---

## รายการ service และ port

| Service       | URL                          | หมายเหตุ                              |
|---------------|------------------------------|---------------------------------------|
| API           | http://localhost:8080/v1     | context-path `/v1`                    |
| Actuator Health | http://localhost:8080/v1/actuator/health | liveness probe |
| Prometheus    | http://localhost:9090        | scrape metrics ทุก 15 วินาที          |
| Grafana       | http://localhost:3000        | user: `admin` / pass: `admin` (dev)   |
| Kibana        | http://localhost:5601        | ดู log แบบ full-text + filter         |
| Elasticsearch | http://localhost:9200        | REST API ของ ES (dev, ไม่มี auth)     |
| PostgreSQL    | localhost:5432               | เข้าได้จาก host ถ้าต้องการ            |

---

## วิธีรัน

```bash
# เข้าไปที่ root ของ repo
cd /path/to/mom_starter_api

# รันครั้งแรก (build Docker image ของ API ด้วย Maven)
docker compose up --build

# รันครั้งต่อไป (ไม่ต้อง build ใหม่ ถ้าโค้ดไม่เปลี่ยน)
docker compose up -d

# ดู log ทุก service
docker compose logs -f

# ดู log แค่ API
docker compose logs -f api

# หยุดทุก service (เก็บ volume ไว้)
docker compose down

# หยุดและลบ volume ทั้งหมด (รีเซ็ตข้อมูลสะอาด)
docker compose down -v
```

> หมายเหตุ: API ต้องรอ postgres healthy ก่อน (`depends_on: postgres: condition: service_healthy`)
> ถ้า API ขึ้นช้าเป็นปกติ — รอ ~1-2 นาทีหลัง postgres พร้อม

---

## ดู Metrics บน Grafana

1. เปิด http://localhost:3000 (login: admin / admin)
2. ไปที่ **Dashboards > Mom Starter > Mom Starter API — Observability**
   (dashboard ถูก provision อัตโนมัติตอน Grafana start)

### Dashboard มี panel อะไรบ้าง

| Panel | ดูอะไร |
|-------|--------|
| API สถานะ (Up/Down) | Prometheus scrape API สำเร็จหรือไม่ — สีเขียว = UP |
| Request Rate (req/s) | จำนวน request รวมต่อวินาที |
| Error Rate 5xx | server error ต่อวินาที — ควรเป็น 0 ตลอด |
| Error Rate 4xx | client error (auth fail, validation) ต่อวินาที |
| Request Rate ต่อ endpoint | แยกตาม method + URI + status — เห็นว่า "เส้นไหนถูกเรียก" |
| Error Rate ต่อ endpoint | 4xx และ 5xx แยกตาม endpoint |
| Latency p50 ต่อ endpoint | response time ที่ 50% ของ request เร็วกว่านี้ |
| Latency p95 ต่อ endpoint | response time ที่ 95% ของ request เร็วกว่านี้ |
| Latency p99 ต่อ endpoint | response time ที่ 99% ของ request เร็วกว่านี้ |

### ตัวอย่าง PromQL queries ที่ใช้ใน dashboard

```promql
# Request rate รวม (req/s)
sum(rate(http_server_requests_seconds_count{application="mom-starter-api"}[1m]))

# Request rate แยก endpoint + status
sum(rate(http_server_requests_seconds_count{application="mom-starter-api"}[1m])) by (uri, method, status)

# p95 latency ต่อ endpoint
histogram_quantile(0.95,
  sum(rate(http_server_requests_seconds_bucket{application="mom-starter-api"}[5m])) by (le, uri, method)
)
```

---

## ดู Log บน Kibana

### ครั้งแรก: สร้าง Data View (Index Pattern)

1. เปิด http://localhost:5601
2. ไปที่ **Stack Management > Kibana > Data Views**
3. คลิก **Create data view**
4. ตั้ง index pattern: `mom-starter-api-*`
5. เลือก timestamp field: `@timestamp`
6. คลิก **Save data view to Kibana**

### ดู Log ใน Discover

1. ไปที่ **Discover** (แถบซ้าย)
2. เลือก data view `mom-starter-api-*`
3. กรอง log ได้ตามต้องการ ตัวอย่าง:

| Filter | ค้นหา |
|--------|--------|
| `level: ERROR` | ดูเฉพาะ error log |
| `status: 401` | ดู unauthorized request ทั้งหมด |
| `path: /v1/auth/login` | ดู log เฉพาะ endpoint |
| `durationMs > 500` | ดู request ที่ช้ากว่า 500ms |
| `requestId: "abc123..."` | trace request เฉพาะ ID |

### Fields ที่ log ทุก request (MDC จาก AccessLogFilter)

| Field | ความหมาย |
|-------|----------|
| `@timestamp` | เวลา request เสร็จ (ISO-8601) |
| `level` | INFO / WARN / ERROR |
| `logger_name` | ชื่อ class ที่ log |
| `message` | "access" (per-request) หรือข้อความ app log |
| `requestId` | ID เฉพาะ request นั้น (12 chars) — ใช้ trace หลาย log line |
| `method` | HTTP method (GET, POST, ...) |
| `path` | URI path (ไม่มี query string — ป้องกัน token หลุด) |
| `status` | HTTP status code (200, 401, 422, ...) |
| `durationMs` | เวลาตอบ request (milliseconds) |

---

## ข้อควรระวังเรื่อง Resource

**Elasticsearch กิน RAM ~1-2 GB** (ตั้ง heap ที่ 512 MB + overhead JVM + OS)

stack นี้ต้องการ RAM รวม ~3-4 GB:

| Service | RAM โดยประมาณ |
|---------|---------------|
| API (Spring Boot) | ~400 MB |
| PostgreSQL | ~200 MB |
| Elasticsearch | ~1 GB (limit ตั้งไว้ใน compose) |
| Kibana | ~400 MB |
| Prometheus | ~100 MB |
| Grafana | ~150 MB |
| Filebeat | ~50 MB |

> แนะนำให้ Docker Desktop มี RAM อย่างน้อย **6 GB** (ตั้งใน Settings > Resources)

---

## หมายเหตุความปลอดภัย Actuator

Actuator endpoints (`/v1/actuator/health`, `/v1/actuator/prometheus`) ถูกตั้งให้เข้าได้
โดยไม่ต้อง login ในชุด config นี้ เพื่อให้ Prometheus scrape ได้และ load balancer probe ได้

**Prod บน AWS ต้องทำสิ่งเหล่านี้:**
- ย้าย actuator ไป management port แยก (`management.server.port=8081`)
- ปิด port 8081 ไม่ให้ออก internet (Security Group อนุญาตแค่ VPC internal)
- ตั้ง `management.endpoint.health.show-details: when-authorized`
- ใช้ CloudWatch Container Insights แทน Prometheus (ECS Fargate native)
- ดู infra design ที่ `docs/architecture/infrastructure-diagram.md`

---

## Prod บน AWS ใช้อะไรแทน

stack นี้ใช้สำหรับ **dev/local เท่านั้น** — บน AWS ใช้ managed services แทน:

| Dev (compose นี้) | Prod (AWS) |
|-------------------|-----------|
| Prometheus + Grafana | CloudWatch Metrics + Container Insights |
| Elasticsearch + Kibana | CloudWatch Logs Insights หรือ OpenSearch Service (managed) |
| Filebeat | ECS awslogs log driver (built-in, zero config) |
| PostgreSQL container | RDS PostgreSQL Multi-AZ (managed, encrypted) |

ดูรายละเอียด AWS infra ที่ `docs/architecture/infrastructure-diagram.md`
