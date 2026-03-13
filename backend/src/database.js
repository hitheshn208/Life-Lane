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
    db.run('PRAGMA foreign_keys = ON');

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

    db.run(`
        CREATE TABLE IF NOT EXISTS govt_ambulances (
            vehicle_number TEXT PRIMARY KEY,
            ambulance_name TEXT NOT NULL,
            ambulance_type TEXT NOT NULL,
            registered_hospital TEXT NOT NULL,
            created_at DATETIME DEFAULT CURRENT_TIMESTAMP
        )
    `, (error) => {
        if (error) {
            console.error('Failed to create govt_ambulances table:', error.message);
            return;
        }

        console.log('govt_ambulances table is ready');
    });

    db.run(`
        CREATE TABLE IF NOT EXISTS driver_ambulances (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            driver_id INTEGER NOT NULL,
            vehicle_number TEXT NOT NULL,
            registered_at DATETIME DEFAULT CURRENT_TIMESTAMP,
            UNIQUE(driver_id, vehicle_number),
            FOREIGN KEY(driver_id) REFERENCES drivers(id) ON DELETE CASCADE,
            FOREIGN KEY(vehicle_number) REFERENCES govt_ambulances(vehicle_number)
        )
    `, (error) => {
        if (error) {
            console.error('Failed to create driver_ambulances table:', error.message);
            return;
        }

        console.log('driver_ambulances table is ready');
    });

    db.run(`
        INSERT OR IGNORE INTO govt_ambulances (vehicle_number, ambulance_name, ambulance_type, registered_hospital)
        VALUES
        ('KA19AB1023', 'LifeLine Rapid Response', 'ALS', 'City Hospital'),
        ('KA19AB2201', 'Apollo Emergency One', 'BLS', 'Apollo Hospital'),
        ('KA19AB3320', 'District Care Mobile', 'Patient Transport', 'District Hospital'),
        ('KA19AB8741', 'Manipal Critical Care', 'ALS', 'Manipal Hospital'),
        ('KA19AB8812', 'Aster Med Transit', 'BLS', 'Aster Hospital'),
        ('KA19AB4521', 'Nitte Trauma Support', 'ALS', 'Nitte Hospital'),
        ('KA19AB9912', 'KMC Rural Response', 'BLS', 'KMC Hospital')
    `, (error) => {
        if (error) {
            console.error('Failed to seed govt_ambulances table:', error.message);
            return;
        }

        console.log('govt_ambulances seed is ready');
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

function allQuery(query, params = []) {
    return new Promise((resolve, reject) => {
        db.all(query, params, (error, rows) => {
            if (error) {
                reject(error);
                return;
            }

            resolve(rows);
        });
    });
}

module.exports = {
    db,
    databasePath,
    runQuery,
    getQuery,
    allQuery
};
