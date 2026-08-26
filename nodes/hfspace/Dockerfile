FROM alpine:3.20 AS fetch
ARG XRAY_VER=v25.8.31
ARG TARGETARCH
RUN apk add --no-cache curl unzip \
    && case "$TARGETARCH" in \
         amd64) XRAY_ARCH=linux-64 ;; \
         arm64) XRAY_ARCH=linux-arm64-v8a ;; \
         *) echo "unsupported arch $TARGETARCH" && exit 1 ;; \
       esac \
    && curl -fsSL -o /tmp/xray.zip \
       "https://github.com/XTLS/Xray-core/releases/download/$XRAY_VER/Xray-$XRAY_ARCH.zip" \
    && unzip -o /tmp/xray.zip xray -d /out

FROM alpine:3.20
RUN apk add --no-cache ca-certificates curl tzdata
COPY --from=fetch /out/xray /usr/local/bin/xray
COPY entrypoint.sh /entrypoint.sh
RUN chmod +x /entrypoint.sh
EXPOSE 7860
ENTRYPOINT ["/entrypoint.sh"]
