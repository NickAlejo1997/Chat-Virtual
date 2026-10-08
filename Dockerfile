FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app
COPY pom.xml .
COPY src/java ./src/java
COPY web ./web
RUN mvn -B -DskipTests package

FROM tomcat:11.0-jdk17-temurin
COPY --from=build /app/target/Chat-Virtual.war /usr/local/tomcat/webapps/ROOT.war
EXPOSE 8080
CMD ["sh", "-c", "sed -i 's/port=\"8080\"/port=\"'\"${PORT:-8080}\"'\"/' /usr/local/tomcat/conf/server.xml && catalina.sh run"]
