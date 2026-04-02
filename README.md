# 🚀 Hướng dẫn cài đặt & triển khai

## 1. Yêu cầu chung

Cài đặt các công cụ sau:
### ☕ Java 8
```bash
sudo apt update
sudo apt install openjdk-8-jdk -y
```

### 📦 Maven
```bash
sudo apt install maven -y
```

### 🐳 Docker và Docker Compose
Set up Docker's apt repository:
```bash
# Add Docker's official GPG key:
sudo apt update
sudo apt install ca-certificates curl
sudo install -m 0755 -d /etc/apt/keyrings
sudo curl -fsSL https://download.docker.com/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc
sudo chmod a+r /etc/apt/keyrings/docker.asc

# Add the repository to Apt sources:
sudo tee /etc/apt/sources.list.d/docker.sources <<EOF
Types: deb
URIs: https://download.docker.com/linux/ubuntu
Suites: $(. /etc/os-release && echo "${UBUNTU_CODENAME:-$VERSION_CODENAME}")
Components: stable
Architectures: $(dpkg --print-architecture)
Signed-By: /etc/apt/keyrings/docker.asc
EOF

sudo apt update
```

Install the Docker packages:
```bash
sudo apt install docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
```

## 2. Triển khai máy Gateway

### Cài đặt môi trường
Đảm bảo đã cài
- Java 8
- Maven
- Docker

### Build project
```bash
mvn install

cd tagawarescheduler
mvn install
```

### Cấu hình Docker
```bash
cd ~/gateway
nano docker-compose.yaml
```

👉 Sửa `extra_hosts` thành IP của **máy cloud**

### Chạy service
```bash
docker compose up -d
```

## 3. Triển khai máy Cloud

### Cài đặt môi trường
Đảm bảo đã cài
- Java 8
- Maven
- Docker

### Build Docker image
```bash
cd webapp/iot-data-api
docker build -t iot-data-api:v1 .
```

### Cấu hình Docker
```bash
cd ~/cloud
nano docker-compose.yaml
```

👉 Sửa `extra_hosts` thành IP của **máy gateway**

### Chạy service
```bash
docker compose up -d
```