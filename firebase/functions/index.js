const functions = require('firebase-functions');
const admin = require('firebase-admin');
admin.initializeApp();

exports.onOrderStatusChange = functions.firestore
    .document('orders/{orderId}')
    .onUpdate(async (change, context) => {
        const newData = change.after.data();
        const previousData = change.before.data();

        if (newData.status !== previousData.status) {
            // Get buyer and farmer details
            const buyerDoc = await admin.firestore()
                .collection('users')
                .doc(newData.buyerId)
                .get();
            
            const farmerDoc = await admin.firestore()
                .collection('users')
                .doc(newData.farmerId)
                .get();

            const buyer = buyerDoc.data();
            const farmer = farmerDoc.data();

            // Prepare notifications
            const buyerMessage = {
                notification: {
                    title: 'Order Status Updated',
                    body: `Your order for ${newData.productName} has been ${newData.status.toLowerCase()}`
                },
                token: buyer.fcmToken
            };

            const farmerMessage = {
                notification: {
                    title: 'Order Status Updated',
                    body: `Order ${context.params.orderId} is now ${newData.status.toLowerCase()}`
                },
                token: farmer.fcmToken
            };

            // Send notifications
            await Promise.all([
                admin.messaging().send(buyerMessage),
                admin.messaging().send(farmerMessage)
            ]);
        }
    });