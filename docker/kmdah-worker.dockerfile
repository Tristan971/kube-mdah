FROM tristandeloche/kmdah-parent:0.0.1-SNAPSHOT

ADD kmdah-worker/target/kmdah-worker.jar /mangahome/kmdah-worker.jar

ENV JARFILE /mangahome/kmdah-worker.jar