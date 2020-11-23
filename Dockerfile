FROM adoptopenjdk:15

RUN apt update && apt install -y dumb-init

WORKDIR /mangahome
ADD target/kmdah.jar /mangahome/kmdah.jar

ENV JAVA_TOOL_OPTIONS "-XX:+UseShenandoahGC -Xms128M -Xmx512M"

ENTRYPOINT [ "dumb-init", "--rewrite", "2:15", "--" ]
CMD [ "java", "-jar", "--enable-preview", "/mangahome/kmdah.jar" ]
