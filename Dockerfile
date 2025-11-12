FROM ubuntu:22.04

# Install essential build tools
RUN apt-get update && \
    apt-get install -y \
        build-essential \
        libzstd-dev \
        vim \
        cmake \
        gawk \
        autoconf \
        file \
        unzip \
        zip \
        git \
        jtreg7 \
        openjdk-21-jdk \
        libasound2-dev \
        libcups2-dev \
        libfontconfig1-dev \
        libx11-dev libxext-dev libxrender-dev libxrandr-dev libxtst-dev libxt-dev

# Working directory
WORKDIR /home/jeandle/

# Default command for interactive development
CMD ["bash"]
