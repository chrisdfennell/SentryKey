# Use a stable, lightweight Node.js base image
FROM node:20-alpine

# Set the working directory
WORKDIR /usr/src/app

# Copy dependency definitions
COPY package*.json ./

# Install only production dependencies
RUN npm ci --omit=dev

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
