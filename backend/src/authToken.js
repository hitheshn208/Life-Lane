const jwt = require('jsonwebtoken');

const JWT_SECRET = process.env.JWT_SECRET || 'change_this_secret_in_production';
const JWT_EXPIRES_IN = process.env.JWT_EXPIRES_IN || '7d';

function generateAuthToken(driver) {
    const resolvedDriverId = driver.driver_id || driver.driverId || driver.id;

    return jwt.sign(
        {
            driverId: resolvedDriverId,
            driver_id: resolvedDriverId,
            phone: driver.phone,
            name: driver.name
        },
        JWT_SECRET,
        { expiresIn: JWT_EXPIRES_IN }
    );
}

function verifyAuthToken(req, res, next) {
    const authHeader = req.headers.authorization || '';
    const [scheme, token] = authHeader.split(' ');

    if (scheme !== 'Bearer' || !token) {
        return res.status(401).json({
            isValid: false,
            message: 'Authorization token is required. Use: Bearer <token>'
        });
    }

    try {
        const payload = jwt.verify(token, JWT_SECRET);
        req.user = payload;
        return next();
    } catch (_error) {
        return res.status(401).json({
            isValid: false,
            message: 'Invalid or expired token'
        });
    }
}

module.exports = {
    generateAuthToken,
    verifyAuthToken
};
