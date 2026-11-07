FROM gradle:8.6-jdk17 AS builder
WORKDIR /app
COPY . .
RUN gradle shadowJar --no-daemon

FROM eclipse-temurin:17-jre-alpine
# Fonts for Java2D infographic rendering (alpine ships none).
RUN apk add --no-cache fontconfig ttf-dejavu
WORKDIR /app
COPY --from=builder /app/build/libs/*-all.jar app.jar
COPY entrypoint.sh .
RUN sed -i 's/\r$//' entrypoint.sh && chmod +x entrypoint.sh
ENTRYPOINT ["./entrypoint.sh"]