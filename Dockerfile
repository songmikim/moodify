FROM ksm0200/ubuntu:1.0.0
ARG JAR_PATH=build/libs/moodify-0.0.1-SNAPSHOT.jar
ARG PORT=5000
COPY ${JAR_PATH} app.jar
RUN mkdir uploads

ENV SPRING_PROFILES_ACTIVE=default,prod
ENV DB_URL=**
ENV DB_PASSWORD=**
ENV DB_USERNAME=**

ENV PYTHON_BASE=/python_project/.venv/bin
ENV PYTHON_EMOTION=/python_project/source
ENV KAKAO_APIKEY=**
ENV NAVER_APIKEY=**
ENV NAVER_SECRET=**

ENTRYPOINT ["java", "-Ddb.password=${DB_PASSWORD}","-Ddb.url=${DB_URL}", "-Ddb.username=${DB_USERNAME}", "-Dfile.path=/uploads", "-Dfile.url=/uploads", "-Dpython.base=${PYTHON_BASE}", "-Dpython.emotion=${PYTHON_EMOTION}","-Dkakao.apikey=${KAKAO_APIKEY}","-Dnaver.apikey=${NAVER_APIKEY}","-Dnaver.secret=${NAVER_SECRET}", "-Dspring.profiles.active=${SPRING_PROFILES_ACTIVE}", "-jar", "app.jar"]

EXPOSE ${PORT}