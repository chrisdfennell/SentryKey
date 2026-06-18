# Use a stable, lightweight Node.js base image
FROM node:20-alpine

# Set the working directory
WORKDIR /usr/src/app

# better-sqlite3 is a native addon: Alpine (musl) needs a build toolchain to
# compile it, plus libstdc++ present at runtime.
RUN apk add --no-cache libstdc++

# Copy dependency definitions
COPY package*.json ./

# Install only production dependencies. Build better-sqlite3 with a temporary
# toolchain that is removed afterward to keep the final image slim.
RUN apk add --no-cache --virtual .build-deps python3 make g++ \
 && npm ci --omit=dev \
 && apk del .build-deps

# Copy the rest of the application source code
COPY . .

# Expose the default port
EXPOSE 3000

# Set environment defaults (can be overridden at runtime)
ENV PORT=3000
ENV MAX_BACKUPS_RETAINED=15
ENV NODE_ENV=production

# Persist backups folder across container rebuilds/updates
VOLUME [ "/usr/src/app/backups" ]

# Start the web and backup server
CMD [ "node", "server.js" ]
