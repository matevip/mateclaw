# Docker buildx builder setup
.PHONY: builder-create builder-rm builder-inspect

BUILDER_NAME := multi-builder

builder-create:
	docker buildx create \
	  --driver docker-container \
	  --driver-opt network=host \
	  --use \
	  --config ~/.docker/buildkitd.toml \
	  --name $(BUILDER_NAME)

builder-rm:
	docker buildx rm $(BUILDER_NAME)

builder-inspect:
	docker buildx inspect --bootstrap

# Docker build targets
# Convention: bare name = SGCCR (singapore), -gz suffix = CCR (guangzhou)

METACLAW_SERVER_IMAGE_TAG := 1.2.3-SNAPSHOT
MATECLAW_SERVER_SG_IMAGE := sgccr.ccs.tencentyun.com/connor-ai-lab/mateclaw-server:$(METACLAW_SERVER_IMAGE_TAG)
MATECLAW_SERVER_GZ_IMAGE := ccr.ccs.tencentyun.com/connor-ai-lab/mateclaw-server:$(METACLAW_SERVER_IMAGE_TAG)
# MATECLAW_SERVER_IMAGE := connor-mateclaw-registry.zeabur.app/mateclaw/mateclaw-server:$(IMAGE_TAG)
# MATECLAW_SERVER_TENCENT_IMAGE := ccr.ccs.tencentyun.com/connor-ai-lab/mateclaw/mateclaw-server:$(IMAGE_TAG)

SEARXNG_IMAGE_TAG := 1.0.1-SNAPSHOT
SEARXNG_SG_IMAGE := sgccr.ccs.tencentyun.com/connor-ai-lab/mateclaw-searxng:$(SEARXNG_IMAGE_TAG)
SEARXNG_GZ_IMAGE := ccr.ccs.tencentyun.com/connor-ai-lab/mateclaw-searxng:$(SEARXNG_IMAGE_TAG)

XRAY_IMAGE_TAG := 1.0.2-SNAPSHOT
XRAY_GZ_IMAGE := ccr.ccs.tencentyun.com/connor-ai-lab/xray-client:$(XRAY_IMAGE_TAG)

MIHOMO_IMAGE_TAG := 1.0.2-SNAPSHOT
MIHOMO_GZ_IMAGE := ccr.ccs.tencentyun.com/connor-ai-lab/mihomo-client:$(MIHOMO_IMAGE_TAG)

# mateclaw-server
build:
	docker buildx build \
	  --platform linux/amd64 \
	  --no-cache \
	  -f mateclaw-server/Dockerfile \
	  --build-arg MAVEN_FLAGS="-Paliyun-first" \
	  -t $(MATECLAW_SERVER_SG_IMAGE) \
	  --push \
	  --progress=plain .

build-gz:
	docker buildx build \
	  --platform linux/amd64 \
	  --no-cache \
	  -f mateclaw-server/Dockerfile \
	  --build-arg MAVEN_FLAGS="-Paliyun-first" \
	  -t $(MATECLAW_SERVER_GZ_IMAGE) \
	  --push \
	  --progress=plain .

# searxng
pull-searxng:
	docker pull --platform linux/amd64 searxng/searxng:latest

build-searxng:
	docker buildx build \
	  --platform linux/amd64 \
	  -f docker/searxng/Dockerfile \
	  -t $(SEARXNG_SG_IMAGE) \
	  --push \
	  --progress=plain .

build-searxng-gz:
	docker buildx build \
	  --platform linux/amd64 \
	  -f docker/searxng/Dockerfile \
	  -t $(SEARXNG_GZ_IMAGE) \
	  --push \
	  --progress=plain .

# xray
build-xray-gz:
	docker buildx build \
	  --platform linux/amd64 \
	  -f docker/xray/Dockerfile \
	  -t $(XRAY_GZ_IMAGE) \
	  --push \
	  --progress=plain .

# mihomo
build-mihomo-gz:
	docker buildx build \
	  --platform linux/amd64 \
	  -f docker/mihomo/Dockerfile \
	  -t $(MIHOMO_GZ_IMAGE) \
	  --push \
	  --progress=plain .
