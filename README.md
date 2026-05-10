# my-own-s3

A minimal S3-compatible object storage server built with Javalin, Kotlin, and SQLite. Objects are stored on disk, metadata in SQLite, and a daily cleanup job deletes expired objects.

## Build & Run

```bash
./gradlew build
./gradlew run
```

Server starts on `http://localhost:9393` by default.

### CLI Options

```
--port <int>                Server port (default: 9393)
--db <path>                 SQLite database file path (default: storage/s3.db)
--allowed-ips <ips>         Comma-separated IPs allowed to create buckets and put objects
--default-expiry-days <int> Default bucket expiry in days when header is not set (default: 30)
```

Example:

```bash
./gradlew run --args="--port 8080 --db mydata.db --allowed-ips 127.0.0.1,192.168.1.10 --default-expiry-days 7"
```

## S3-Compatible API

| Operation   | Method | Path               | Notes                                          |
|-------------|--------|--------------------|-------------------------------------------------|
| ListBuckets | GET    | `/`                | Returns XML list of all buckets                 |
| CreateBucket| PUT    | `/{bucket}`        | Optional header `x-amz-meta-expiry-days`        |
| HeadBucket  | HEAD   | `/{bucket}`        | 200 if exists, 404 if not                       |
| PutObject   | PUT    | `/{bucket}/{key}`  | Raw body, returns ETag                          |
| HeadObject  | HEAD   | `/{bucket}/{key}`  | Returns ETag, Content-Length, Content-Type       |
| GetObject   | GET    | `/{bucket}/{key}`  | Returns file content with ETag, Content-Length   |

### Bucket Expiry

Buckets have an expiry duration. A daily background job deletes objects (and their files) older than the bucket's expiry. Set expiry via the `x-amz-meta-expiry-days` header on CreateBucket, or it defaults to `--default-expiry-days`.

### IP Restriction

When `--allowed-ips` is set, only those IPs can call CreateBucket and PutObject. GetObject is always public.

## Testing with MinIO Client (mc)

### 1. Start the server

```bash
./gradlew run
```

### 2. Set up mc alias

Credentials can be anything -- auth is not validated.

```bash
mc alias set local http://localhost:9393 minioadmin minioadmin
```

### 3. Create a bucket

```bash
mc mb local/my-bucket
```

### 4. Upload a file

```bash
echo "hello world" > test.txt
mc cp test.txt local/my-bucket/test.txt
```

### 5. Download a file

```bash
mc cat local/my-bucket/test.txt
```

### 6. Check object info

```bash
mc stat local/my-bucket/test.txt
```

### 7. List buckets

```bash
mc ls local
```

### 8. Upload with nested key

```bash
mc cp test.txt local/my-bucket/folder/subfolder/test.txt
mc cat local/my-bucket/folder/subfolder/test.txt
```

## Testing with curl

```bash
# Create bucket with 7-day expiry
curl -X PUT http://localhost:9393/my-bucket -H "x-amz-meta-expiry-days: 7"

# Upload file
curl -X PUT http://localhost:9393/my-bucket/hello.txt \
  -H "Content-Type: text/plain" -d "Hello World"

# Download file
curl http://localhost:9393/my-bucket/hello.txt

# List buckets
curl http://localhost:9393/

# Check object metadata
curl -I http://localhost:9393/my-bucket/hello.txt
```

## Running Tests

```bash
./gradlew test
```
