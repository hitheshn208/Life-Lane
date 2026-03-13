const fs = require('fs');
const path = require('path');
const sqlite3 = require('sqlite3').verbose();

const dataDirectory = path.join(__dirname, '..', 'data');
const databasePath = path.join(dataDirectory, 'drivers.db');

fs.mkdirSync(dataDirectory, { recursive: true });

const db = new sqlite3.Database(databasePath, (error) => {
    if (error) {
        console.error('Failed to connect to SQLite database:', error.message);
        return;
    }

    console.log(`Connected to SQLite database at ${databasePath}`);
});

db.serialize(() => {
    db.run(`
        CREATE TABLE IF NOT EXISTS drivers (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            name TEXT NOT NULL,
            phone TEXT UNIQUE NOT NULL,
            license_number TEXT UNIQUE NOT NULL,
            password TEXT,
            created_at DATETIME DEFAULT CURRENT_TIMESTAMP
        )
    `, (error) => {
        if (error) {
            console.error('Failed to create drivers table:', error.message);
            return;
        }

        console.log('Drivers table is ready');
    });
});

function runQuery(query, params = []) {
    return new Promise((resolve, reject) => {
        db.run(query, params, function onRun(error) {
            if (error) {
                reject(error);
                return;
            }

            resolve({
                lastID: this.lastID,
                changes: this.changes
            });
        });
    });
}

function getQuery(query, params = []) {
    return new Promise((resolve, reject) => {
        db.get(query, params, (error, row) => {
            if (error) {
                reject(error);
                return;
            }

            resolve(row);
        });
    });
}

module.exports = {
    db,
    databasePath,
    runQuery,
    getQuery
};
