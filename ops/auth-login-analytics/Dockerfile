FROM node:22.23.4-alpine3.23
WORKDIR /app
COPY package*.json ./
RUN npm ci --omit=dev --ignore-scripts --no-audit --no-fund
COPY index.mjs lib.mjs ./
USER node
CMD ["node", "index.mjs"]
