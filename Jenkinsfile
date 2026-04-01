pipeline {
    agent any

    environment {
        DOCKER_HOST  = "tcp://172.17.0.1:2376"
        REGISTRY     = "172.17.0.1:5000"
        IMAGE_NAME   = "myapp"
        SECRETS_PATH = "/opt/vault-agent/secrets"
    }

    stages {

        stage('Checkout') {
            steps {
                checkout scm
            }
        }

        stage('Verify Secrets Available') {
            steps {
                sh """
                    test -f ${SECRETS_PATH}/client.crt || (echo "ERROR: client cert not found" && exit 1)
                    test -f ${SECRETS_PATH}/client.key || (echo "ERROR: client key not found" && exit 1)
                    test -f ${SECRETS_PATH}/ca.pem     || (echo "ERROR: CA cert not found" && exit 1)
                    test -f ${SECRETS_PATH}/writer-pass.txt || (echo "ERROR: writer pass not found" && exit 1)
                    test -f ${SECRETS_PATH}/reader-pass.txt || (echo "ERROR: reader pass not found" && exit 1)
                    echo "All secrets available"
                """
            }
        }

        stage('Prepare Docker Config') {
            steps {
                sh """
                    # Writer config
                    mkdir -p /tmp/docker-writer
                    WRITER_PASS=\$(cat ${SECRETS_PATH}/writer-pass.txt)
                    AUTH_WRITER=\$(echo -n "writer:\$WRITER_PASS" | base64 -w 0)
                    echo '{"auths":{"${REGISTRY}":{"auth":"'\$AUTH_WRITER'"}}}' > /tmp/docker-writer/config.json

                    # Reader config
                    mkdir -p /tmp/docker-reader
                    READER_PASS=\$(cat ${SECRETS_PATH}/reader-pass.txt)
                    AUTH_READER=\$(echo -n "reader:\$READER_PASS" | base64 -w 0)
                    echo '{"auths":{"${REGISTRY}":{"auth":"'\$AUTH_READER'"}}}' > /tmp/docker-reader/config.json
                """
            }
        }

        stage('Pull cache layers') {
            steps {
                sh """
                    docker --config /tmp/docker-writer \
                      --tlsverify \
                      --tlscacert=${SECRETS_PATH}/ca.pem \
                      --tlscert=${SECRETS_PATH}/client.crt \
                      --tlskey=${SECRETS_PATH}/client.key \
                      -H ${DOCKER_HOST} \
                      pull ${REGISTRY}/${IMAGE_NAME}:builder || true

                    docker --config /tmp/docker-writer \
                      --tlsverify \
                      --tlscacert=${SECRETS_PATH}/ca.pem \
                      --tlscert=${SECRETS_PATH}/client.crt \
                      --tlskey=${SECRETS_PATH}/client.key \
                      -H ${DOCKER_HOST} \
                      pull ${REGISTRY}/${IMAGE_NAME}:latest || true
                """
            }
        }

        stage('Build') {
            steps {
                sh """
                    docker --config /tmp/docker-writer \
                      --tlsverify \
                      --tlscacert=${SECRETS_PATH}/ca.pem \
                      --tlscert=${SECRETS_PATH}/client.crt \
                      --tlskey=${SECRETS_PATH}/client.key \
                      -H ${DOCKER_HOST} \
                      build \
                      --cache-from ${REGISTRY}/${IMAGE_NAME}:builder \
                      --target builder \
                      -t ${REGISTRY}/${IMAGE_NAME}:builder \
                      .

                    docker --config /tmp/docker-writer \
                      --tlsverify \
                      --tlscacert=${SECRETS_PATH}/ca.pem \
                      --tlscert=${SECRETS_PATH}/client.crt \
                      --tlskey=${SECRETS_PATH}/client.key \
                      -H ${DOCKER_HOST} \
                      build \
                      --cache-from ${REGISTRY}/${IMAGE_NAME}:builder \
                      --cache-from ${REGISTRY}/${IMAGE_NAME}:latest \
                      -t ${REGISTRY}/${IMAGE_NAME}:${BUILD_NUMBER} \
                      -t ${REGISTRY}/${IMAGE_NAME}:latest \
                      .
                """
            }
        }

        stage('Push') {
            steps {
                sh """
                    docker --config /tmp/docker-writer \
                      --tlsverify \
                      --tlscacert=${SECRETS_PATH}/ca.pem \
                      --tlscert=${SECRETS_PATH}/client.crt \
                      --tlskey=${SECRETS_PATH}/client.key \
                      -H ${DOCKER_HOST} \
                      push ${REGISTRY}/${IMAGE_NAME}:builder

                    docker --config /tmp/docker-writer \
                      --tlsverify \
                      --tlscacert=${SECRETS_PATH}/ca.pem \
                      --tlscert=${SECRETS_PATH}/client.crt \
                      --tlskey=${SECRETS_PATH}/client.key \
                      -H ${DOCKER_HOST} \
                      push ${REGISTRY}/${IMAGE_NAME}:${BUILD_NUMBER}

                    docker --config /tmp/docker-writer \
                      --tlsverify \
                      --tlscacert=${SECRETS_PATH}/ca.pem \
                      --tlscert=${SECRETS_PATH}/client.crt \
                      --tlskey=${SECRETS_PATH}/client.key \
                      -H ${DOCKER_HOST} \
                      push ${REGISTRY}/${IMAGE_NAME}:latest
                """
            }
        }

        stage('Verify Reader Pull') {
            steps {
                sh """
                    docker --config /tmp/docker-reader \
                      --tlsverify \
                      --tlscacert=${SECRETS_PATH}/ca.pem \
                      --tlscert=${SECRETS_PATH}/client.crt \
                      --tlskey=${SECRETS_PATH}/client.key \
                      -H ${DOCKER_HOST} \
                      pull ${REGISTRY}/${IMAGE_NAME}:latest

                    echo "Reader pull: SUCCESS"
                """
            }
        }
    }

    post {
        always {
            sh """
                rm -rf /tmp/docker-writer /tmp/docker-reader || true
            """
        }
    }
}