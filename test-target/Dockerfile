FROM node:22-alpine
ENV NODE_ENV=production PORT=3000
WORKDIR /app
COPY package.json server.js ./
# Bad-deploy demo: build with --build-arg RELEASE=bad to bake a release whose /health fails (see README).
ARG RELEASE=good
RUN echo "$RELEASE" > /app/RELEASE
USER node
EXPOSE 3000
CMD ["node", "server.js"]
